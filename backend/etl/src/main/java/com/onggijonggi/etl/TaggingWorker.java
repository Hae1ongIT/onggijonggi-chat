package com.onggijonggi.etl;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : TaggingWorker.java
 * Description : 방 문서 태깅 작업(#362). 문서 처리(IngestionWorkers)와 따로 도는 스레드 하나가, 검색 준비가 끝난 문서의 현재 회차 중
 *               태그가 없거나·태깅 설정이 바뀌었거나·실패해 다시 할 때가 된 것을 골라 태그만 붙인다(조각·임베딩은 다시 하지 않는다).
 *               새 문서, 다시 만들기(#348)로 새로 생긴 회차, 첫 배포의 기존 문서, 설정 변경이 모두 이 한 경로로 처리된다.
 *               태깅이 늦거나 실패해도 문서 상태에는 영향이 없다 — 문서는 이미 "검색 준비 완료"이고, 태그가 없는 동안 검색의 태그 채널에만
 *               걸리지 않는다. 태깅 서버 설정이 비면 돌지 않는다(본문을 다른 곳으로 대신 보내지 않는다).
 *               로그에는 문서·회차 ID와 건수·시간만 남긴다(본문·태그 원문은 남기지 않는다).
 */
@Component
public class TaggingWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TaggingWorker.class);
	/** 종료 때 진행 중인 태깅을 기다리는 상한. 끊긴 태깅은 다음 기동에서 다시 대상이 된다. */
	private static final Duration STOP_WAIT = Duration.ofSeconds(5);

	private final TagStore store;
	private final Tagger tagger;
	private final TagIndex index;
	private final ChunkIndex chunks;
	private final IngestionWorkers workers;
	private final TaggingProperties settings;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Thread thread;
	/** 태깅이 꺼진 이유. null이면 돌고 있다. */
	private volatile String disabledReason;

	public TaggingWorker(TagStore store, Tagger tagger, TagIndex index, ChunkIndex chunks, IngestionWorkers workers, TaggingProperties settings) {
		this.store = store;
		this.tagger = tagger;
		this.index = index;
		this.chunks = chunks;
		this.workers = workers;
		this.settings = settings;
	}

	@Override
	public void start() {
		if (running.get()) return;
		if (!settings.enabled()) {
			disabledReason = "태깅 서버 주소·모델(app.etl.tagging.url·model, compose는 TAGGING_URL·TAGGING_MODEL)이 비어 있다";
			log.info("문서 태깅을 시작하지 않는다: {}", disabledReason);
			return;
		}
		running.set(true);
		thread = new Thread(this::loop, "etl-tagging");
		thread.setDaemon(true);
		thread.start();
		log.info("문서 태깅 스레드 시작(모델 {} · 카테고리 {}개)", settings.model(), settings.categories().size());
	}

	private void loop() {
		boolean released = false;
		while (running.get()) {
			int done = 0;
			try {
				// 문서 처리가 꺼져 있거나 조각 인덱스 준비·이전이 끝나지 않았으면 기다린다 — 태그만 앞서 쌓이지 않게.
				if (!released) released = releaseFailures();
				if (workers.isRunning() && chunks.prepared() && !chunks.migrating()) done = cycle();
			} catch (Throwable error) {
				log.warn("문서 태깅 대상을 읽지 못했다 — 잠시 뒤 다시 본다", error);
			}
			if (done == 0) pause();
		}
	}

	/** 기동 뒤 한 번, 지난 실패를 바로 다시 시도하게 푼다(설정·주소를 고쳐 재기동한 경우). 끝냈으면 true. */
	boolean releaseFailures() {
		int released = store.releaseFailures();
		if (released > 0) log.info("지난 태깅 실패 {}건을 바로 다시 시도한다(기동 — 태깅 설정·서버 주소가 바뀌었을 수 있다)", released);
		return true;
	}

	/** 대상 한 묶음을 태깅한다. 처리한 수를 돌려준다. */
	int cycle() {
		index.ensure();
		String fingerprint = settings.fingerprint();
		List<TagStore.Target> targets = store.targets(fingerprint, settings.batch());
		for (TagStore.Target target : targets) {
			if (!running.get()) break;
			// 일시 장애면 묶음의 나머지를 지금 시도하지 않고 쉰다 — 태깅 서버가 잠깐 끊긴 사이 대기 문서가 한꺼번에 실패로 밀리지 않게.
			if (!tag(target, fingerprint)) return 0;
		}
		return targets.size();
	}

	/** 한 회차를 태깅한다. 일시 장애로 실패하면 false다. */
	boolean tag(TagStore.Target target, String fingerprint) {
		long started = System.nanoTime();
		try {
			TagPrompt.Tags tags = tagger.tag(target.job());
			index.write(target, tags, fingerprint);
			if (!store.saveDone(target, tags, fingerprint)) {
				// 태깅하는 동안 문서가 지워졌거나 회차가 정리됐다. 방금 쓴 태그 문서를 거둔다.
				index.delete(target.document(), target.runSeq());
				log.info("태깅 중 정리된 회차라 태그를 버린다: doc={} run={}", target.document(), target.runSeq());
				return true;
			}
			log.info("문서 태깅 완료: doc={} run={} 분류={} 키워드 {}개 ({}ms)", target.document(), target.runSeq(),
					tags.classified() ? "됨" : "미분류", tags.keywords().size(), (System.nanoTime() - started) / 1_000_000);
			return true;
		} catch (EtlFailure failure) {
			// 영구 실패는 기록했으면 다음 문서로 간다. 기록하지 못했으면 같은 문서가 곧바로 다시 잡히므로 쉰다.
			return failed(target, failure.code(), failure.permanent(), fingerprint, failure) && failure.permanent();
		} catch (RuntimeException unexpected) {
			failed(target, "UNEXPECTED", false, fingerprint, unexpected);
			return false;
		}
	}

	/** 실패를 남긴다. 남겼으면 true. */
	private boolean failed(TagStore.Target target, String code, boolean permanent, String fingerprint, RuntimeException cause) {
		if (!running.get()) {
			// 종료 중 끊긴 요청이다. 실패로 남기면 다음 기동 뒤에도 간격만큼 밀린다 — 남기지 않고 다음 기동 때 바로 다시 한다.
			log.info("종료 중이라 태깅을 멈춘다 — 다음 기동 때 다시 한다: doc={} run={}", target.document(), target.runSeq());
			return false;
		}
		String stored = code.length() > 64 ? code.substring(0, 64) : code;
		// 영구 실패(입력 거절·원본 없음 등)는 다시 해도 같으니 긴 간격을 둔다. 설정·주소를 고쳐 재기동하면 바로 다시 대상이 된다(releaseFailures).
		Duration first = permanent ? settings.permanentRetryDelay() : settings.retryFirstDelay();
		Duration max = permanent ? settings.permanentRetryDelay() : settings.retryDelay();
		try {
			store.saveFailed(target, stored, fingerprint, first, max);
		} catch (RuntimeException error) {
			log.warn("태깅 실패를 기록하지 못했다 — 잠시 뒤 다시 대상이 된다: doc={} run={}", target.document(), target.runSeq(), error);
			return false;
		}
		// 예기치 못한 오류(코드 결함)만 스택을 남긴다. 태깅 서버 장애는 문서마다 반복되므로 한 줄로 남긴다.
		String retry = permanent ? "영구 실패 — " + max + " 뒤 또는 ETL 재기동 때 다시 시도" : "일시 장애 — 간격을 늘려 가며 다시 시도";
		if ("UNEXPECTED".equals(code))
			log.warn("문서 태깅 실패({}, {}): doc={} run={}", code, retry, target.document(), target.runSeq(), cause);
		else
			log.warn("문서 태깅 실패({}, {}): doc={} run={}", code, retry, target.document(), target.runSeq());
		return true;
	}

	private void pause() {
		try {
			Thread.sleep(settings.idleDelay().toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			running.set(false);
		}
	}

	@Override
	public void stop() {
		running.set(false);
		Thread current = thread;
		if (current == null) return;
		current.interrupt();
		try {
			current.join(STOP_WAIT.toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	/** 태깅이 꺼져 있으면 주기적으로 다시 알린다(검색의 태그 채널을 켰는데 태그가 붙지 않는 이유를 찾게). */
	@Scheduled(fixedDelayString = "${app.etl.disabled-warn-delay:5m}", initialDelayString = "${app.etl.disabled-warn-delay:5m}")
	public void warnIfDisabled() {
		if (disabledReason != null) log.info("문서 태깅이 꺼져 있다 — 검색의 태그 채널에 쓸 태그가 붙지 않는다: {}", disabledReason);
	}
}
