package com.onggijonggi.api.auth.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Class Name : KeycloakAdminSettingsCheckTest.java
 * Description : BFF 전용 Keycloak 관리 클라이언트 설정 점검(#326). 빈 secret과, 관리 client id가 로그인 client id와 같은 예전 구성을
 *               경고하고, 경고 문장에 비밀값을 싣지 않는다.
 */
class KeycloakAdminSettingsCheckTest {

	/** 기동 완료 이벤트에 걸려 있고, 문제마다 warn을 한 번씩 남긴다. */
	@Test
	void itRunsOnApplicationReadyAndWarnsForEachProblem() throws Exception {
		assertThat(KeycloakAdminSettingsCheck.class.getMethod("warnOnce")
				.getAnnotation(org.springframework.context.event.EventListener.class).value())
				.containsExactly(org.springframework.boot.context.event.ApplicationReadyEvent.class);
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(KeycloakAdminSettingsCheck.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			new KeycloakAdminSettingsCheck("ogjg-client", "", "ogjg-client").warnOnce();
		} finally {
			logger.detachAppender(appender);
		}
		assertThat(appender.list).hasSize(2)
				.allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
	}

	@Test
	void aDedicatedClientWithASecretHasNoProblem() {
		assertThat(KeycloakAdminSettingsCheck.problems("ogjg-bff", "bff-secret", "ogjg-client")).isEmpty();
	}

	@Test
	void anEmptySecretIsReported() {
		assertThat(KeycloakAdminSettingsCheck.problems("ogjg-bff", "", "ogjg-client")).singleElement().asString()
				.contains("app.keycloak.admin.client-secret");
		assertThat(KeycloakAdminSettingsCheck.problems("ogjg-bff", null, "ogjg-client")).hasSize(1);
	}

	@Test
	void reusingTheLoginClientIsReportedWithoutTheSecret() {
		assertThat(KeycloakAdminSettingsCheck.problems("ogjg-client", "login-secret", "ogjg-client")).singleElement()
				.asString().contains("로그인 client id와 같다").doesNotContain("login-secret");
	}
}
