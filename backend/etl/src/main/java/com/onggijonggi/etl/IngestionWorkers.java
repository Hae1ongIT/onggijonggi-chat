package com.onggijonggi.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
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
	private final EtlProperties properties;
	private final AtomicBoolean running = new AtomicBoolean();
	private final List<Thread> threads = new ArrayList<>();

	public IngestionWorkers(RunStore runs, IngestionPipeline pipeline, EmbeddingClient embeddings, EtlProperties properties) {
		this.runs = runs;
		this.pipeline = pipeline;
		this.embeddings = embeddings;
		this.properties = properties;
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) return;
		if (!embeddings.configured()) {
			log.warn("임베딩 주소(app.etl.embedding.url)가 비어 있어 문서 처리를 시작하지 않는다");
			return;
		}
		for (int i = 0; i < Math.max(1, properties.concurrency()); i++) {
			Thread thread = new Thread(this::loop, "etl-worker-" + i);
			thread.start();
			threads.add(thread);
		}
		log.info("문서 처리 스레드 {}개 시작(임베딩 {} · 모델 {})", threads.size(), properties.embedding().url(), embeddings.model());
	}

	private void loop() {
		while (running.get()) {
			RunStore.Job job;
			try {
				job = runs.claim(properties.lease());
			} catch (RuntimeException error) {
				log.warn("처리 회차를 가져오지 못했다 — 잠시 뒤 다시 본다", error);
				job = null;
			}
			if (job == null) {
				pause();
				continue;
			}
			pipeline.process(job);
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

	@Override
	public void stop() {
		running.set(false);
		// 처리 중인 회차는 끝까지 둔다. 중간에 끊겨도 선점 시한이 지나면 다시 집힌다.
		threads.forEach(Thread::interrupt);
		threads.clear();
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}
}
