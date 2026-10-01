package com.onggijonggi.api.auth.keycloak;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Class Name : KeycloakAdminSettingsCheck.java
 * Description : BFF 전용 Keycloak 관리 클라이언트 설정(#326)을 기동 때 한 번 점검해 경고한다. 기동은 막지 않는다 — 설정이
 *               빠져도 채팅은 돌고, 관리 조회는 실패해 화면(KEYCLOAK_ADMIN_UNAVAILABLE)과 권한 변경 감사 수집 상태로도
 *               드러난다. 로그인 클라이언트로 대신 조회하지 않는다(그 secret은 프론트에도 있다).
 *               두 가지를 본다: 관리 secret이 비었는지, 관리 client id가 로그인 client id와 같은지(예전 구성을 그대로 둔 경우).
 *               비밀값은 로그에 쓰지 않는다.
 */
@Component
public class KeycloakAdminSettingsCheck {

	private static final Logger log = LoggerFactory.getLogger(KeycloakAdminSettingsCheck.class);

	private final String adminClientId;
	private final String adminClientSecret;
	private final String loginClientId;

	public KeycloakAdminSettingsCheck(@Value("${app.keycloak.admin.client-id}") String adminClientId,
			@Value("${app.keycloak.admin.client-secret}") String adminClientSecret,
			@Value("${app.security.expected-audience:}") String loginClientId) {
		this.adminClientId = adminClientId;
		this.adminClientSecret = adminClientSecret;
		this.loginClientId = loginClientId;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warnOnce() {
		for (String problem : problems(adminClientId, adminClientSecret, loginClientId)) log.warn(problem);
	}

	static List<String> problems(String adminClientId, String adminClientSecret, String loginClientId) {
		List<String> problems = new ArrayList<>();
		if (adminClientSecret == null || adminClientSecret.isBlank()) {
			problems.add("Keycloak 관리 클라이언트 secret(app.keycloak.admin.client-secret)이 비어 있다 — 표시 이름·사람 목록·"
					+ "초대 검색·권한 변경 감사가 동작하지 않는다. INSTALL.md의 「Keycloak 관리 클라이언트와 권한 변경 감사」를 따른다");
		}
		if (adminClientId != null && !adminClientId.isBlank() && adminClientId.equals(loginClientId)) {
			problems.add("Keycloak 관리 client id(" + adminClientId + ")가 로그인 client id와 같다 — 로그인 secret을 가진 프론트도"
					+ " 관리 토큰을 받을 수 있는 예전 구성이다. BFF 전용 클라이언트로 옮긴다(INSTALL.md「Keycloak 관리 클라이언트와"
					+ " 권한 변경 감사」)");
		}
		return problems;
	}
}
