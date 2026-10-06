package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : IngestionWorkersTest.java
 * Description : 처리 스레드가 예외 하나로 죽지 않는지, 임베딩 주소가 없거나 차원이 매핑과 다르면 처리를 시작하지 않는지,
 *               종료가 처리 중인 회차를 인터럽트하지 않는지 확인한다.
 */
class IngestionWorkersTest {

	private final RunStore runs = mock(RunStore.class);
	private final IngestionPipeline pipeline = mock(IngestionPipeline.class);
	private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
	private final ChunkIndex index = mock(ChunkIndex.class);
	private final RunStore.Job job = new RunStore.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			1, 1, "a.txt", "a".repeat(64), null);

	private IngestionWorkers workers() {
		var properties = new EtlProperties(null, null, new EtlProperties.Embedding("http://embedding", "bge-m3", 1024, 32),
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofMillis(20),
				Duration.ofMinutes(10), List.of(Duration.ofSeconds(30)), Duration.ofSeconds(5));
		return new IngestionWorkers(runs, pipeline, embeddings, index, properties);
	}

	@Test
	void aFailureWhileProcessingDoesNotKillTheWorkerThread() {
		when(embeddings.configured()).thenReturn(true);
		when(embeddings.dimensions()).thenReturn(1024);
		when(index.mappingDimensions()).thenReturn(1024);
		when(runs.claim(any(), anyInt())).thenReturn(job, job, job).thenReturn(null);
		doThrow(new IllegalStateException("상태 확정 중 DB 끊김")).doNothing().when(pipeline).process(job);
		var workers = workers();
		workers.start();
		try {
			verify(pipeline, timeout(5000).atLeast(3)).process(job);
			verify(runs, atLeast(3)).claim(Duration.ofMinutes(10), 2);
		} finally {
			workers.stop();
		}
		assertThat(workers.isRunning()).isFalse();
	}

	@Test
	void doesNotStartWithoutAnEmbeddingEndpointOrWithAMismatchedDimension() {
		when(embeddings.configured()).thenReturn(false);
		var unconfigured = workers();
		unconfigured.start();
		assertThat(unconfigured.isRunning()).isFalse();
		unconfigured.stop();

		when(embeddings.configured()).thenReturn(true);
		when(embeddings.dimensions()).thenReturn(768);
		when(index.mappingDimensions()).thenReturn(1024);
		var mismatched = workers();
		mismatched.start();
		assertThat(mismatched.isRunning()).isFalse();
		mismatched.stop();
		verify(runs, never()).claim(any(), anyInt());
	}

	@Test
	void stopWaitsForTheRunningJobInsteadOfInterruptingIt() throws Exception {
		when(embeddings.configured()).thenReturn(true);
		when(embeddings.dimensions()).thenReturn(1024);
		when(index.mappingDimensions()).thenReturn(1024);
		when(runs.claim(any(), anyInt())).thenReturn(job).thenReturn(null);
		var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
		var started = new java.util.concurrent.CountDownLatch(1);
		org.mockito.Mockito.doAnswer(invocation -> {
			started.countDown();
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
			return null;
		}).when(pipeline).process(job);
		var workers = workers();
		workers.start();
		try {
			assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		} finally {
			workers.stop();
		}
		assertThat(interrupted).isFalse();
	}
}
