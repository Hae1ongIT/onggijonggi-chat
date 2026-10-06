package com.onggijonggi.api.rag;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.chat.ChatMessage;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import com.onggijonggi.api.chat.ThreadDocumentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadDocumentSearch.java
 * Description : 방 문서 검색(#344)의 흐름. ① 방 접근 확인과 검색 범위(ThreadDocumentService.searchScope — 실패는 404, 검색 전에 끝난다)
 *               → ② 대상 문서가 없으면 근거 없음(임베딩·LLM을 부르지 않는다) → ③ 설정·모델 확인 → ④ 후속 질문이면 검색 문장 다시 쓰기
 *               → ⑤ 질문 임베딩 → ⑥ 하이브리드 검색. 장애는 UNAVAILABLE로 돌려주고 근거 없음으로 숨기지 않는다.
 *               권한은 턴 시작 시 한 번 확인한다 — 답변이 닿는 길(협업방 구독·기록 재조회)은 기존 회수·접근 확인이 지킨다(D 협의 5).
 */
@Service
public class ThreadDocumentSearch {

	private static final Logger log = LoggerFactory.getLogger(ThreadDocumentSearch.class);

	private final ThreadDocumentService documents;
	private final QueryRewriter rewriter;
	private final QueryEmbedder embedder;
	private final ChunkSearcher searcher;

	public ThreadDocumentSearch(ThreadDocumentService documents, QueryRewriter rewriter, QueryEmbedder embedder, ChunkSearcher searcher) {
		this.documents = documents;
		this.rewriter = rewriter;
		this.embedder = embedder;
		this.searcher = searcher;
	}

	/** history는 질문 앞의 대화(오래된 것부터), modelId는 그 대화의 모델(다시 쓰기 기본값)이다. */
	public Mono<SearchResult> search(UUID thread, CurrentActor actor, String question, List<ChatMessage> history, String modelId) {
		return Mono.fromCallable(() -> run(thread, documents.searchScope(thread, actor), question, history, modelId))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private SearchResult run(UUID thread, ThreadDocumentScope scope, String question, List<ChatMessage> history, String modelId) {
		if (scope.targets().isEmpty()) return new SearchResult(SearchResult.Status.NO_EVIDENCE, question, false, List.of());
		long started = System.nanoTime();
		try {
			if (!embedder.configured()) throw new RagUnavailableException("임베딩 주소(app.rag.embedding.url)가 비어 있다");
			for (ThreadDocumentScope.Target target : scope.targets()) {
				// 다른 모델로 임베딩한 문서 벡터와 질문 벡터를 섞지 않는다(지도 선택 10). 모델을 바꿨으면 문서를 다시 처리해야 한다.
				if (!embedder.model().equals(target.embeddingModel()) || embedder.dimensions() != target.dimensions())
					throw new RagUnavailableException("문서 임베딩 모델(" + target.embeddingModel() + "/" + target.dimensions()
							+ ")이 검색 설정(" + embedder.model() + "/" + embedder.dimensions() + ")과 다르다 — 재처리가 필요하다: doc=" + target.document());
			}
			QueryRewriter.Result query = rewriter.rewrite(question, history, modelId);
			List<ChunkSearcher.Hit> hits = searcher.search(scope, query.query(), embedder.embed(query.query()));
			Map<String, ThreadDocumentScope.Target> byId = scope.targets().stream()
					.collect(Collectors.toMap(target -> target.document().toString(), Function.identity()));
			List<SearchResult.Chunk> chunks = hits.stream().filter(hit -> byId.containsKey(hit.document()))
					.map(hit -> new SearchResult.Chunk(UUID.fromString(hit.document()), byId.get(hit.document()).fileName(), hit.runSeq(),
							hit.seq(), hit.loc(), hit.content(), hit.vectorScore(), hit.keywordScore()))
					.toList();
			log.debug("방 문서 검색: thread={} 대상 {}건 결과 {}건 다시 쓰기 {} {}ms", thread, scope.targets().size(), chunks.size(),
					query.rewritten(), (System.nanoTime() - started) / 1_000_000);
			return new SearchResult(chunks.isEmpty() ? SearchResult.Status.NO_EVIDENCE : SearchResult.Status.FOUND, query.query(),
					query.rewritten(), chunks);
		} catch (RagUnavailableException unavailable) {
			log.warn("방 문서 검색을 끝내지 못했다: thread={} {}", thread, unavailable.getMessage(), unavailable.getCause());
			return new SearchResult(SearchResult.Status.UNAVAILABLE, question, false, List.of());
		}
	}
}
