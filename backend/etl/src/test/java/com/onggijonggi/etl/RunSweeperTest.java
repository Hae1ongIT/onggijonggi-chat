package com.onggijonggi.etl;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : RunSweeperTest.java
 * Description : 한 회차의 청크 정리가 계속 실패해도 뒤의 회차 정리가 막히지 않는지 확인한다.
 */
class RunSweeperTest {

	@Test
	void oneFailingRunDoesNotStarveTheRest() {
		RunStore runs = mock(RunStore.class);
		ChunkIndex index = mock(ChunkIndex.class);
		var stuck = new RunStore.Stale(UUID.randomUUID(), UUID.randomUUID(), 1);
		var next = new RunStore.Stale(UUID.randomUUID(), UUID.randomUUID(), 2);
		when(runs.stale(100, java.time.Duration.ofMinutes(2))).thenReturn(List.of(stuck, next));
		doThrow(EtlFailure.transientFailure("INDEX_UNAVAILABLE", "ES 장애", null)).when(index).delete(stuck.document(), 1);

		new RunSweeper(runs, index, mock(TagIndex.class), mock(TagStore.class), java.time.Duration.ofMinutes(2)).sweep();

		verify(runs, never()).purged(stuck);
		verify(runs).postpone(stuck);
		verify(index).delete(next.document(), 2);
		verify(runs).purged(next);
	}

	@Test
	void nothingIsDeletedWhileTheIndexIsBeingMigrated() {
		RunStore runs = mock(RunStore.class);
		ChunkIndex index = mock(ChunkIndex.class);
		when(index.migrating()).thenReturn(true);

		new RunSweeper(runs, index, mock(TagIndex.class), mock(TagStore.class), java.time.Duration.ofMinutes(2)).sweep();

		verify(runs, never()).stale(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
		verify(index, never()).delete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
	}

	/** 정리하는 회차의 태그(#362)도 지운다. 태그를 못 지우면 정리 완료로 표시하지 않아 다음 주기에 다시 지운다. */
	@Test
	void theTagsOfAPurgedRunAreDroppedBeforeItIsMarkedPurged() {
		RunStore runs = mock(RunStore.class);
		ChunkIndex index = mock(ChunkIndex.class);
		TagIndex tags = mock(TagIndex.class);
		TagStore tagStore = mock(TagStore.class);
		var done = new RunStore.Stale(UUID.randomUUID(), UUID.randomUUID(), 1);
		var stuck = new RunStore.Stale(UUID.randomUUID(), UUID.randomUUID(), 2);
		when(runs.stale(100, java.time.Duration.ofMinutes(2))).thenReturn(List.of(done, stuck));
		doThrow(EtlFailure.transientFailure("TAG_INDEX_UNAVAILABLE", "ES 장애", null)).when(tags).delete(stuck.document(), 2);

		new RunSweeper(runs, index, tags, tagStore, java.time.Duration.ofMinutes(2)).sweep();

		var order = org.mockito.Mockito.inOrder(tags, tagStore, runs);
		order.verify(tags).delete(done.document(), 1);
		order.verify(tagStore).delete(done.document(), 1);
		order.verify(runs).purged(done);
		verify(runs, never()).purged(stuck);
		verify(runs).postpone(stuck);
	}
}
