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
		when(runs.stale(20)).thenReturn(List.of(stuck, next));
		doThrow(EtlFailure.transientFailure("INDEX_UNAVAILABLE", "ES 장애", null)).when(index).delete(stuck.document(), 1);

		new RunSweeper(runs, index).sweep();

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

		new RunSweeper(runs, index).sweep();

		verify(runs, never()).stale(org.mockito.ArgumentMatchers.anyInt());
		verify(index, never()).delete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
	}
}
