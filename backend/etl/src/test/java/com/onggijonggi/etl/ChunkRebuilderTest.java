package com.onggijonggi.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Class Name : ChunkRebuilderTest.java
 * Description : 다시 만들 일을 찾는 주기(#348)의 분기 — 처리가 꺼졌거나 인덱스 준비 전이면 아무것도 하지 않고, 청크가 모자란 문서만
 *               복구하며, ES 대조가 실패하면 그 주기를 건너뛰고, 지워진 인덱스는 다시 만든 뒤 바로 대조하는지.
 */
class ChunkRebuilderTest {

	private final RebuildStore rebuilds = mock(RebuildStore.class);
	private final ChunkIndex index = mock(ChunkIndex.class);
	private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
	private final IngestionWorkers workers = mock(IngestionWorkers.class);
	private ChunkRebuilder rebuilder;

	@BeforeEach
	void setUp() {
		var properties = new EtlProperties(null, null, null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1),
				Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		rebuilder = new ChunkRebuilder(rebuilds, index, embeddings, workers, properties, Duration.ofHours(1));
		when(workers.isRunning()).thenReturn(true);
		when(index.prepared()).thenReturn(true);
		when(embeddings.model()).thenReturn("bge-m3");
		when(embeddings.dimensions()).thenReturn(1024);
	}

	@Test
	void nothingHappensWhileProcessingIsOffOrTheIndexIsNotReady() {
		when(workers.isRunning()).thenReturn(false);
		rebuilder.tick();
		when(workers.isRunning()).thenReturn(true);
		when(index.migrating()).thenReturn(true);
		rebuilder.tick();
		when(index.migrating()).thenReturn(false);
		when(index.prepared()).thenReturn(false);
		rebuilder.tick();

		verifyNoInteractions(rebuilds);
	}

	@Test
	void onlyDocumentsMissingChunksAreRecovered() {
		var whole = new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3);
		var partial = new RebuildStore.CurrentRun(UUID.randomUUID(), 2, 3);
		var gone = new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 2);
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(whole, partial, gone));
		when(index.runCounts(anyList())).thenReturn(Map.of(ChunkIndex.runKey(whole.document(), 1), 3L,
				ChunkIndex.runKey(partial.document(), 2), 1L, ChunkIndex.runKey(gone.document(), 2), 2L));

		rebuilder.tick();

		verify(rebuilds, never()).queueRecover(whole);
		verify(rebuilds).queueRecover(partial);
		verify(rebuilds).queueRecover(gone);
	}

	@Test
	void anIncompleteCountSkipsTheCycleInsteadOfRebuildingEverything() {
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3)));
		when(index.runCounts(anyList())).thenThrow(EtlFailure.transientFailure("INDEX_UNAVAILABLE", "불완전", null));

		rebuilder.tick();

		verify(rebuilds, never()).queueRecover(any());
	}

	@Test
	void aDeletedIndexIsRecreatedAndCheckedOnTheNextCycle() {
		var run = new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3);
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(run));
		when(index.runCounts(anyList())).thenAnswer(call -> {
			when(index.prepared()).thenReturn(false);
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "별칭 없음", null);
		});
		rebuilder.tick();
		verify(index, never()).ensure();

		doReturn(Map.of()).when(index).runCounts(anyList());
		rebuilder.tick();

		verify(index).ensure();
		verify(rebuilds).queueRecover(run);
	}

	@Test
	void outdatedDocumentsAreQueuedUntilNoneAreLeft() {
		when(rebuilds.queueOutdated(anyString(), anyInt(), anyString(), anyInt())).thenReturn(ChunkRebuilder.AUTO_LIMIT, 3);

		rebuilder.tick();
		rebuilder.tick();
		rebuilder.tick();

		verify(rebuilds, times(2)).queueOutdated("bge-m3", 1024, "para-v1:800/1200/100", ChunkRebuilder.AUTO_LIMIT);
	}
}
