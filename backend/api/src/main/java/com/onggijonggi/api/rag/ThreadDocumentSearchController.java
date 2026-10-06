package com.onggijonggi.api.rag;

import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.api.chat.ChatMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Class Name : ThreadDocumentSearchController.java
 * Description : 방 문서 검색 확인 API(#344). 채팅 답변 연결(E) 전에 "이 방에서 이 질문으로 무엇을 찾는지"를 실제 임베딩으로 확인한다.
 *               방 문서 목록과 같은 접근 확인을 거치고(없는 방·남의 방은 404), 결과에는 그 방 참여자가 이미 열람할 수 있는 원본의
 *               청크만 나온다. app.rag.search-api-enabled=false면 404다.
 */
@RestController
public class ThreadDocumentSearchController {

	/** history는 질문 앞의 대화(오래된 것부터), modelId는 그 대화의 모델(다시 쓰기에 쓴다). */
	public record SearchRequest(@NotBlank @Size(max = 2000) String question, @Size(max = 20) List<@Valid ChatMessage> history,
			@Size(max = 100) String modelId) { }

	private final CurrentActorProvider actors;
	private final ThreadDocumentSearch search;
	private final boolean enabled;

	public ThreadDocumentSearchController(CurrentActorProvider actors, ThreadDocumentSearch search, RagProperties properties) {
		this.actors = actors;
		this.search = search;
		this.enabled = properties.searchApiEnabled();
	}

	@PostMapping("/api/threads/{threadId}/documents/search")
	public Mono<SearchResult> search(@PathVariable UUID threadId, @Valid @RequestBody SearchRequest request) {
		if (!enabled) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
		return actors.currentActor().flatMap(actor -> search.search(threadId, actor, request.question(),
				request.history() == null ? List.of() : request.history(), request.modelId()));
	}
}
