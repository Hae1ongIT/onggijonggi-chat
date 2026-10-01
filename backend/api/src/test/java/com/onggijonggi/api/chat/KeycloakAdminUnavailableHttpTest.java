package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Class Name : KeycloakAdminUnavailableHttpTest.java
 * Description : BFF 전용 Keycloak 관리 클라이언트의 설정 문제(#326)가 HTTP 응답까지 그대로 가는지 확인한다 — 관리 조회가
 *               KeycloakAdminUnavailableException으로 실패하면 503과 KEYCLOAK_ADMIN_UNAVAILABLE 코드가 나가고, 본문에 Keycloak
 *               경로·사유가 실리지 않는다. 서비스나 컨트롤러가 새로 예외를 삼키면 여기서 드러난다. casbin 프로필 없이 뜨는
 *               절체 사전 검증으로 본다(사람 목록·CSV 임포트·초대 검색의 전파는 각 서비스 테스트가 본다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ FakeChatModelConfig.class, FakeJwtDecoderConfig.class, KeycloakAdminUnavailableHttpTest.Unavailable.class })
class KeycloakAdminUnavailableHttpTest {

	@LocalServerPort
	private int port;
	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void aConfigurationProblemReachesTheScreenAsItsOwnCode() {
		client.post().uri("/api/platform/rbac/cutover-validation")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("platform", List.of("PLATFORM_ADMIN"), List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("KEYCLOAK_ADMIN_UNAVAILABLE")
				.jsonPath("$.error.message").isEqualTo("사용자 정보를 불러올 수 없습니다.")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	/** 관리 조회가 역할 누락(403)으로 실패하는 Keycloak. */
	@TestConfiguration
	static class Unavailable {

		@Bean
		@Primary
		KeycloakAdminClient unavailableKeycloakAdminClient() {
			return new KeycloakAdminClient(WebClient.builder(), "http://unused", "app-realm", "ogjg-bff", "unused",
					Duration.ZERO) {
				@Override
				public Mono<List<String>> listEnabledUserSubjects() {
					return Mono.error(new KeycloakAdminUnavailableException(KeycloakAdminUnavailableException.Reason.FORBIDDEN,
							403, "/admin/realms/app-realm/users"));
				}
			};
		}
	}
}
