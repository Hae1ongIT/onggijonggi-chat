package com.onggijonggi.api.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.rag.ChunkRebuildInProgressException;
import com.onggijonggi.api.rag.ChunkRebuildService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : ChunkRebuildHttpTest.java
 * Description : 방 문서 일괄 재처리 API(#348)의 권한(PLATFORM_ADMIN 전용), 입력 검증, 202·409·404 응답 모양을 확인한다. 요청 저장·진행
 *               집계는 ChunkRebuildPostgresTest가 본다. rag 테스트지만 chat 패키지에 둔다 — 공용 픽스처가 package-private이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class ChunkRebuildHttpTest {

	@LocalServerPort
	private int port;

	@MockitoBean
	private ChunkRebuildService service;

	private RestTestClient client;
	private final UUID id = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void aPlatformAdminQueuesARebuild() {
		when(service.request(ChunkRebuildService.Scope.OUTDATED, "rebuild-admin")).thenReturn(rebuild());

		post(admin(), "{\"scope\":\"OUTDATED\"}").expectStatus().isAccepted()
				.expectBody().jsonPath("$.id").isEqualTo(id.toString()).jsonPath("$.status").isEqualTo("PENDING");
	}

	@Test
	void onlyPlatformAdminsMayRebuild() {
		post("Bearer " + TestJwtSupport.signedJwt("rebuild-user", List.of("USER")), "{\"scope\":\"ALL\"}").expectStatus().isForbidden();
		for (String path : List.of("/api/platform/rag/rebuilds", "/api/platform/rag/rebuilds/" + id))
			client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("rebuild-user", List.of("USER")))
					.exchange().expectStatus().isForbidden();
		verify(service, never()).request(any(), any());
	}

	@Test
	void anUnknownScopeIsRejected() {
		for (String body : List.of("{\"scope\":\"EVERYTHING\"}", "{}", "{\"scope\":\"all\"}", "{\"scope\":", ""))
			post(admin(), body).expectStatus().isBadRequest();
		client.post().uri("/api/platform/rag/rebuilds").header(HttpHeaders.AUTHORIZATION, admin())
				.contentType(MediaType.TEXT_PLAIN).body("ALL").exchange().expectStatus().isEqualTo(415);
		client.post().uri("/api/platform/rag/rebuilds").contentType(MediaType.APPLICATION_JSON).body("{\"scope\":\"ALL\"}")
				.exchange().expectStatus().isUnauthorized();
		verify(service, never()).request(any(), any());
	}

	@Test
	void aRequestWhileAnotherIsRunningIsAConflictNamingTheRunningOne() {
		when(service.request(eq(ChunkRebuildService.Scope.ALL), any())).thenThrow(new ChunkRebuildInProgressException(id));

		post(admin(), "{\"scope\":\"ALL\"}").expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.error.code").isEqualTo("REBUILD_IN_PROGRESS")
				.jsonPath("$.error.message").value(message -> org.assertj.core.api.Assertions.assertThat((String) message).contains(id.toString()));
	}

	@Test
	void progressIsReadableAndAnUnknownRequestIs404() {
		when(service.recent()).thenReturn(List.of(rebuild()));
		when(service.find(id)).thenReturn(Optional.of(rebuild()));

		client.get().uri("/api/platform/rag/rebuilds").header(HttpHeaders.AUTHORIZATION, admin()).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$[0].scope").isEqualTo("OUTDATED");
		client.get().uri("/api/platform/rag/rebuilds/" + id).header(HttpHeaders.AUTHORIZATION, admin()).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.remaining").isEqualTo(0);
		client.get().uri("/api/platform/rag/rebuilds/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin()).exchange()
				.expectStatus().isNotFound();
	}

	private RestTestClient.ResponseSpec post(String authorization, String body) {
		return client.post().uri("/api/platform/rag/rebuilds").header(HttpHeaders.AUTHORIZATION, authorization)
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private ChunkRebuildService.Rebuild rebuild() {
		return new ChunkRebuildService.Rebuild(id, ChunkRebuildService.Scope.OUTDATED, "PENDING", "rebuild-admin", Instant.now(), null, null, 0, 0);
	}

	private static String admin() {
		return "Bearer " + TestJwtSupport.signedJwt("rebuild-admin", List.of("PLATFORM_ADMIN"));
	}
}
