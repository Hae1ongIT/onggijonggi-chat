package com.onggijonggi.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
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
 *               복구하며, ES 대조가 실패하면 그 주기를 건너뛰고, 지워진 인덱스는 다시 만든 뒤 바로 대조하는지. 대조의 페이지 이어 읽기·
 *               상한에서 멈춘 자리부터 이어 보기, 대조 주기, 대기 상한, 설정 변경 재처리의 반복 조건도 본다.
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
		when(index.runCounts(anyMap())).thenReturn(Map.of(whole.document(), 3L, partial.document(), 1L));

		rebuilder.tick();

		verify(rebuilds, never()).queueRecover(whole);
		verify(rebuilds).queueRecover(partial);
		verify(rebuilds).queueRecover(gone);
	}

	@Test
	void anIncompleteCountSkipsTheCycleInsteadOfRebuildingEverything() {
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3)));
		when(index.runCounts(anyMap())).thenThrow(EtlFailure.transientFailure("INDEX_UNAVAILABLE", "불완전", null));

		rebuilder.tick();

		verify(rebuilds, never()).queueRecover(any());
	}

	@Test
	void aDeletedIndexIsRecreatedAndCheckedOnTheNextCycle() {
		var run = new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3);
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(run));
		when(index.runCounts(anyMap())).thenAnswer(call -> {
			when(index.prepared()).thenReturn(false);
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "별칭 없음", null);
		});
		rebuilder.tick();
		verify(index, never()).ensure();

		doReturn(Map.of()).when(index).runCounts(anyMap());
		rebuilder.tick();

		verify(index).ensure();
		verify(rebuilds).queueRecover(run);
	}

	@Test
	void aCheckThatHitTheLimitResumesWhereItStoppedOnTheNextCycle() {
		List<RebuildStore.CurrentRun> page = java.util.stream.IntStream.range(0, ChunkRebuilder.VERIFY_PAGE)
				.mapToObj(i -> new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3)).toList();
		when(rebuilds.currentRuns(any(), anyInt())).thenReturn(page, List.of());
		when(index.runCounts(anyMap())).thenReturn(Map.of());
		when(rebuilds.queueRecover(any())).thenReturn(true);

		rebuilder.tick();
		rebuilder.tick();

		verify(rebuilds, times(ChunkRebuilder.AUTO_LIMIT)).queueRecover(any());
		verify(rebuilds).currentRuns(null, ChunkRebuilder.VERIFY_PAGE);
		verify(rebuilds).currentRuns(page.get(ChunkRebuilder.AUTO_LIMIT - 1).document(), ChunkRebuilder.VERIFY_PAGE);
	}

	@Test
	void automaticRebuildsWaitWhileTheBacklogIsFullButOperatorRequestsStillRun() {
		when(rebuilds.backlog()).thenReturn(ChunkRebuilder.BACKLOG_LIMIT);

		rebuilder.tick();

		verify(rebuilds).handleNextRequest(anyString(), anyInt(), anyString());
		verify(rebuilds, never()).queueOutdated(anyString(), anyInt(), anyString(), anyInt());
		verify(rebuilds, never()).currentRuns(any(), anyInt());
	}

	@Test
	void aFullPageIsFollowedByTheNextPageAndAFinishedCheckStartsOverNextTime() {
		List<RebuildStore.CurrentRun> full = java.util.stream.IntStream.range(0, ChunkRebuilder.VERIFY_PAGE)
				.mapToObj(i -> new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3)).toList();
		List<RebuildStore.CurrentRun> last = List.of(new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3));
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(full);
		when(rebuilds.currentRuns(full.get(ChunkRebuilder.VERIFY_PAGE - 1).document(), ChunkRebuilder.VERIFY_PAGE)).thenReturn(last);
		when(index.runCounts(anyMap())).thenAnswer(call -> {
			Map<UUID, Integer> asked = call.getArgument(0);
			return asked.containsKey(last.get(0).document()) ? Map.of() : asked.keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 3L));
		});
		when(rebuilds.queueRecover(any())).thenReturn(true);

		rebuilder.verify();
		rebuilder.verify();

		verify(rebuilds, times(2)).queueRecover(last.get(0));
		verify(rebuilds, times(2)).currentRuns(null, ChunkRebuilder.VERIFY_PAGE);
	}

	@Test
	void theCheckRunsOncePerDelayAndAFailedIndexRecreationRetriesNextCycle() {
		rebuilder.tick();
		rebuilder.tick();
		verify(rebuilds, times(1)).currentRuns(null, ChunkRebuilder.VERIFY_PAGE);

		var run = new RebuildStore.CurrentRun(UUID.randomUUID(), 1, 3);
		ChunkRebuilder fresh = new ChunkRebuilder(rebuilds, index, embeddings, workers, new EtlProperties(null, null, null,
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5)), Duration.ofHours(1));
		when(rebuilds.currentRuns(null, ChunkRebuilder.VERIFY_PAGE)).thenReturn(List.of(run));
		when(index.runCounts(anyMap())).thenAnswer(call -> {
			when(index.prepared()).thenReturn(false);
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "별칭 없음", null);
		});
		fresh.tick();
		org.mockito.Mockito.doThrow(new IllegalStateException("ES 아직 안 뜸")).when(index).ensure();
		org.mockito.Mockito.clearInvocations(rebuilds);
		fresh.tick();

		verify(rebuilds, never()).handleNextRequest(anyString(), anyInt(), anyString());
		verify(rebuilds, never()).queueRecover(any());
		org.mockito.Mockito.doNothing().when(index).ensure();
		doReturn(Map.of()).when(index).runCounts(anyMap());
		fresh.tick();
		verify(rebuilds).queueRecover(run);
	}

	@Test
	void outdatedDocumentsAreQueuedUntilNoneAreLeft() {
		when(rebuilds.queueOutdated(anyString(), anyInt(), anyString(), anyInt())).thenReturn(ChunkRebuilder.AUTO_LIMIT, 3);

		rebuilder.tick();
		rebuilder.tick();
		rebuilder.tick();

		verify(rebuilds, times(2)).queueOutdated("bge-m3", 1024, new EtlProperties.Chunk(800, 1200, 100).settings().fingerprint(), ChunkRebuilder.AUTO_LIMIT);
	}
}
