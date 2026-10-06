package com.onggijonggi.etl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : IngestionWorkers.java
 * Description : 설정한 수(app.etl.concurrency)만큼 처리 스레드를 띄워 각자 회차를 선점·처리한다. 할 일이 없으면 폴링 간격만큼 쉰다.
 *               임베딩 주소가 비어 있으면 작업을 가져가지 않는다 — 가져갔다가 전부 실패로 쌓이는 것보다 PENDING으로 두는 편이 낫다.
 */
@Component
public class IngestionWorkers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IngestionWorkers.class);

	private final RunStore runs;
	private final IngestionPipeline pipeline;
	private final EmbeddingClient embeddings;
	private final ChunkIndex index;
	private final EtlProperties properties;
	/** 종료 시 처리 중인 회차를 기다리는 상한. compose 기본 종료 유예(10초)보다 짧아야 강제 종료 전에 끝난다. */
	private static final Duration STOP_WAIT = Duration.ofSeconds(8);

	private final AtomicBoolean running = new AtomicBoolean();
	/** 처리하지 않는 이유. null이면 처리 중이다. 주기 경고에 쓴다. */
	private volatile String disabledReason;
	/** 스레드마다 지금 처리 중인 회차. 종료 대기 안에 끝나지 못한 회차를 놓아줄 때 쓴다. */
	private final java.util.Map<Thread, RunStore.Job> current = new java.util.concurrent.ConcurrentHashMap<>();
	private final List<Thread> threads = new ArrayList<>();

	public IngestionWorkers(RunStore runs, IngestionPipeline pipeline, EmbeddingClient embeddings, ChunkIndex index,
			EtlProperties properties) {
		this.runs = runs;
		this.pipeline = pipeline;
		this.embeddings = embeddings;
		this.index = index;
		this.properties = properties;
	}

	@Override
	public void start() {
		if (running.get()) return;
		if (!embeddings.configured()) {
			disabledReason = "임베딩 주소(app.etl.embedding.url, compose는 EMBEDDING_URL)가 비어 있다";
			log.warn("문서 처리를 시작하지 않는다: {}", disabledReason);
			return;
		}
		if (embeddings.dimensions() != index.mappingDimensions()) {
			// 차원이 다르면 모든 적재가 거절돼 문서가 전부 실패로 쌓인다. 처리하지 않고 설정을 고치게 한다.
			disabledReason = "임베딩 차원(" + embeddings.dimensions() + ")이 검색 인덱스 매핑 차원(" + index.mappingDimensions() + ")과 다르다";
			log.error("문서 처리를 시작하지 않는다: {}", disabledReason);
			return;
		}
		running.set(true);
		for (int i = 0; i < Math.max(1, properties.concurrency()); i++) {
			Thread thread = new Thread(this::loop, "etl-worker-" + i);
			// 종료 대기(stop)가 끝나도 남은 스레드가 JVM 종료를 막지 않게 한다. 끊긴 회차는 선점 시한 뒤 다시 집힌다.
			thread.setDaemon(true);
			thread.start();
			threads.add(thread);
		}
		log.info("문서 처리 스레드 {}개 시작(임베딩 {} · 모델 {})", threads.size(), properties.embedding().url(), embeddings.model());
	}

	private void loop() {
		while (running.get()) {
			RunStore.Job job;
			try {
				job = runs.claim(properties.lease(), properties.retryDelays().size() + 1);
			} catch (RuntimeException error) {
				log.warn("처리 회차를 가져오지 못했다 — 잠시 뒤 다시 본다", error);
				job = null;
			}
			if (job == null) {
				pause();
				continue;
			}
			current.put(Thread.currentThread(), job);
			try {
				pipeline.process(job);
			} catch (VirtualMachineError fatal) {
				// 메모리 부족 같은 JVM 오류 뒤에는 이 프로세스를 믿을 수 없다. 끝내서 컨테이너가 다시 띄우게 한다. 이 회차는 선점 시한 뒤
				// 다시 집히고, 같은 문서가 계속 죽이면 시도 상한(claim)에서 실패로 끝난다.
				log.error("처리 중 JVM 오류로 워커를 종료한다: doc={} run={}", job.document(), job.runSeq(), fatal);
				Runtime.getRuntime().halt(1);
			} catch (Throwable unexpected) {
				// 상태 확정(DB) 실패 등. 스레드를 살려 두고, 이 회차는 선점 시한 뒤 다시 집힌다.
				log.error("처리 중 예기치 못한 오류 — 선점 시한 뒤 다시 처리한다: doc={} run={}", job.document(), job.runSeq(), unexpected);
			} finally {
				current.remove(Thread.currentThread());
			}
		}
	}

	private void pause() {
		try {
			Thread.sleep(properties.pollDelay().toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			running.set(false);
		}
	}

	/**
	 * 새 회차를 집지 않게 하고 처리 중인 회차가 끝나기를 기다린다. 인터럽트하지 않는다 — 진행 중인 외부 호출을 끊으면 일시 실패로
	 * 분류돼 배포 때마다 시도 횟수를 쓴다. 기다리는 동안 끝나지 않은 회차는 선점 시한 뒤 다른 워커가 다시 집는다.
	 */
	@Override
	public void stop() {
		running.set(false);
		long deadline = System.nanoTime() + STOP_WAIT.toNanos();
		for (Thread thread : threads) {
			try {
				thread.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		// 기다려도 끝나지 못한 회차는 놓아준다 — 선점 시한(10분)을 기다리지 않고 다음 워커가 바로 집고, 이번 시도는 세지 않는다.
		// 남은 스레드는 데몬이라 JVM과 함께 끝나고, 늦게 끝나도 소유 확인(att_cnt)에서 막힌다.
		for (RunStore.Job job : current.values()) {
			try {
				runs.release(job);
				log.info("종료 대기 안에 끝나지 못한 회차를 놓아줬다: doc={} run={}", job.document(), job.runSeq());
			} catch (RuntimeException error) {
				log.warn("회차를 놓아주지 못했다 — 선점 시한 뒤 다시 집힌다: doc={} run={}", job.document(), job.runSeq(), error);
			}
		}
		current.clear();
		threads.clear();
	}

	/** 처리가 꺼져 있으면 주기적으로 다시 알린다. 컨테이너는 떠 있어 기동 로그 한 줄만으로는 놓치기 쉽다. */
	@Scheduled(fixedDelayString = "${app.etl.disabled-warn-delay:5m}", initialDelayString = "${app.etl.disabled-warn-delay:5m}")
	public void warnIfDisabled() {
		if (disabledReason != null) log.warn("문서 처리가 꺼져 있다 — 등록 문서가 처리 대기에 머문다: {}", disabledReason);
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}
}
