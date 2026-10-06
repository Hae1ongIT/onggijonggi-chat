package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.chat.ThreadDocumentService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadDocumentSearchTest.java
 * Description : 검색 전용 스케줄러(격벽)의 대기열이 가득 차 작업을 받지 못하면 500이 아니라 UNAVAILABLE로 끝나고, 방 범위 조회·외부 호출을
 *               하지 않는지 확인한다. 정상 흐름은 ThreadDocumentSearchIntegrationTest가 본다.
 */
class ThreadDocumentSearchTest {

	@Test
	void aFullSearchQueueIsUnavailableWithoutTouchingAnything() {
		ThreadDocumentService documents = mock(ThreadDocumentService.class);
		QueryEmbedder embedder = mock(QueryEmbedder.class);
		var rejecting = Schedulers.fromExecutor(task -> { throw new RejectedExecutionException("full"); });
		var search = new ThreadDocumentSearch(documents, mock(QueryRewriter.class), embedder, mock(ChunkSearcher.class), rejecting);

		SearchResult result = search.search(UUID.randomUUID(), new CurrentActor(UUID.randomUUID(), "s", "s"), "연차", List.of(), null).block();

		assertThat(result.status()).isEqualTo(SearchResult.Status.UNAVAILABLE);
		verifyNoInteractions(documents, embedder);
	}
}
