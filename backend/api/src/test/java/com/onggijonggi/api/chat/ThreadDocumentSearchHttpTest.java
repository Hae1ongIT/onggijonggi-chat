package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.api.rag.RagProperties;
import com.onggijonggi.api.rag.SearchResult;
import com.onggijonggi.api.rag.ThreadDocumentSearch;
import com.onggijonggi.api.rag.ThreadDocumentSearchController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : ThreadDocumentSearchHttpTest.java
 * Description : 방 문서 검색 확인 API(#344)의 입력 검증, 서비스로 넘기는 값, 결과 모양, 설정으로 끈 경우의 404를 확인한다.
 *               인가·범위·장애 구분은 ThreadDocumentSearchIntegrationTest가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class ThreadDocumentSearchHttpTest {

	@LocalServerPort
	private int port;

	@MockitoBean
	private ThreadDocumentSearch search;

	private RestTestClient client;
	private final UUID room = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void theQuestionHistoryAndModelReachTheSearchAndTheResultIsReturned() {
		UUID doc = UUID.randomUUID();
		when(search.search(eq(room), any(), eq("그럼 그거는?"), anyList(), eq("gemma"))).thenReturn(Mono.just(new SearchResult(
				SearchResult.Status.FOUND, "연차 이월 기한", true,
				List.of(new SearchResult.Chunk(doc, "휴가 규정.pdf", 1, 3, "page=2", "3월 말까지 이월", 0.81, 4.2)))));

		client.post().uri("/api/threads/" + room + "/documents/search")
				.header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
				.body("{\"question\":\"그럼 그거는?\",\"history\":[{\"role\":\"user\",\"content\":\"연차 이월 규정\"}],\"modelId\":\"gemma\"}")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("FOUND")
				.jsonPath("$.query").isEqualTo("연차 이월 기한")
				.jsonPath("$.rewritten").isEqualTo(true)
				.jsonPath("$.chunks[0].documentId").isEqualTo(doc.toString())
				.jsonPath("$.chunks[0].loc").isEqualTo("page=2");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> history = ArgumentCaptor.forClass(List.class);
		verify(search).search(eq(room), any(), eq("그럼 그거는?"), history.capture(), eq("gemma"));
		assertThat(history.getValue()).containsExactly(new ChatMessage("user", "연차 이월 규정"));
	}

	@Test
	void aBlankOrOversizedQuestionIsRejectedBeforeSearching() {
		for (String body : List.of("{\"question\":\" \"}", "{\"question\":\"" + "가".repeat(2001) + "\"}",
				"{\"question\":\"질문\",\"history\":[{\"role\":\"user\",\"content\":\"\"}]}",
				"{\"question\":\"질문\",\"history\":[null]}"))
			client.post().uri("/api/threads/" + room + "/documents/search")
					.header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).body(body)
					.exchange()
					.expectStatus().isBadRequest();
		verify(search, never()).search(any(), any(), any(), any(), any());
	}

	@Test
	void anUnauthenticatedRequestIsRejected() {
		client.post().uri("/api/threads/" + room + "/documents/search")
				.contentType(MediaType.APPLICATION_JSON).body("{\"question\":\"연차\"}")
				.exchange()
				.expectStatus().isUnauthorized();
		verify(search, never()).search(any(), any(), any(), any(), any());
	}

	@Test
	void aMissingHistoryIsAnEmptyConversation() {
		when(search.search(eq(room), any(), eq("연차"), eq(List.of()), eq(null)))
				.thenReturn(Mono.just(new SearchResult(SearchResult.Status.NO_EVIDENCE, "연차", false, List.of())));

		client.post().uri("/api/threads/" + room + "/documents/search")
				.header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).body("{\"question\":\"연차\"}")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("NO_EVIDENCE");
	}

	@Test
	void theApiCanBeTurnedOff() {
		ThreadDocumentSearch unused = Mockito.mock(ThreadDocumentSearch.class);
		var off = new ThreadDocumentSearchController(Mockito.mock(CurrentActorProvider.class), unused,
				new RagProperties(null, null, null, null, false));

		StepVerifier.create(off.search(room, new ThreadDocumentSearchController.SearchRequest("연차", List.of(), null)))
				.expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
				.verify();
		verify(unused, never()).search(any(), any(), any(), any(), any());
	}

	private static String bearer() {
		return "Bearer " + TestJwtSupport.signedJwt("doc-search-user", List.of("USER"));
	}
}
