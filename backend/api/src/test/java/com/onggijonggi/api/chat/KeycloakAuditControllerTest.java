package com.onggijonggi.api.chat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : KeycloakAuditControllerTest.java
 * Description : Keycloak 권한 변경 감사 조회(#304)의 HTTP 경계를 Docker 없이 확인한다 — 토큰이 없으면 401, PLATFORM_ADMIN이
 *               아니면 403, 틀린 필터는 DB에 닿기 전에 400 봉투. 조회·수집 자체는 KeycloakAuditKeycloakTest가 실제 Keycloak으로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ FakeChatModelConfig.class, FakeJwtDecoderConfig.class })
class KeycloakAuditControllerTest {

	private static final String PATH = "/api/platform/rbac/keycloak-audits";

	@LocalServerPort
	private int port;
	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void requiresAToken() {
		client.get().uri(PATH).exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
	}

	@Test
	void onlyThePlatformAdminCanRead() {
		get(PATH, List.of("USER")).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void invalidFiltersAreBadRequests() {
		for (String query : List.of("limit=0", "limit=101", "eventKind=NOPE", "from=yesterday",
				"from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z", "cursor=garbage", "role=", "targetSubject=" + "x".repeat(256))) {
			get(PATH + "?" + query, List.of("PLATFORM_ADMIN"))
					.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
					.expectBody().jsonPath("$.error.code").exists();
		}
	}

	private RestTestClient.ResponseSpec get(String path, List<String> roles) {
		return client.get().uri(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("kc-audit-" + UUID.randomUUID(), roles))
				.exchange();
	}
}
