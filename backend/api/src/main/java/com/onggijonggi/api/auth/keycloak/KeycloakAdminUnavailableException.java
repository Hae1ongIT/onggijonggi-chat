package com.onggijonggi.api.auth.keycloak;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : KeycloakAdminUnavailableException.java
 * Description : BFF 전용 Keycloak 관리 클라이언트(#326)로 관리 조회를 할 수 없고, 다시 시도해도 풀리지 않는 경우다 —
 *               토큰 발급 거부(클라이언트 id·secret이 틀림, 서비스 계정이 꺼짐)와 Admin API 403(서비스 계정 역할 누락).
 *               서버 설정 문제라 503이고, GlobalExceptionHandler가 KEYCLOAK_ADMIN_UNAVAILABLE 코드를 붙여 화면이
 *               "잠시 후 다시" 대신 관리자에게 문의하라고 안내하게 한다. Keycloak 연결 실패·5xx·토큰 무효화(Admin API 401)는
 *               일시 장애라 이 예외로 바꾸지 않는다. 사유와 상태·경로만 담고 Keycloak 응답 본문은 담지 않는다.
 */
public class KeycloakAdminUnavailableException extends ResponseStatusException {

	public enum Reason {
		/** 토큰 엔드포인트가 클라이언트를 거부했다(400·401). */
		TOKEN_REJECTED,
		/** Admin API가 403이다 — 서비스 계정에 필요한 realm-management 역할이 없다. */
		FORBIDDEN
	}

	private final Reason reason;
	private final int keycloakStatus;
	private final String path;

	public KeycloakAdminUnavailableException(Reason reason, int keycloakStatus, String path) {
		super(HttpStatus.SERVICE_UNAVAILABLE, reason + " " + keycloakStatus + " " + path);
		this.reason = reason;
		this.keycloakStatus = keycloakStatus;
		this.path = path;
	}

	public Reason reason() {
		return reason;
	}

	/** 같은 사유·상태·경로의 새 예외(기억해 둔 거부를 다시 알릴 때). */
	public KeycloakAdminUnavailableException again() {
		return new KeycloakAdminUnavailableException(reason, keycloakStatus, path);
	}

	/**
	 * 수집 상태·로그에 남길 요지. 비밀값과 Keycloak 응답 본문은 넣지 않는다. 토큰 거부의 상태는 받은 그대로 적는다 —
	 * Keycloak 26은 id·secret이 틀릴 때, 서비스 계정이 꺼졌을 때, 없는 클라이언트일 때 모두 401이라(실측) 상태로 원인을
	 * 가를 수는 없다. 원인은 콘솔에서 확인한다.
	 */
	public String summary() {
		return reason == Reason.TOKEN_REJECTED ? "Keycloak 관리 클라이언트 인증 실패(설정 확인, " + keycloakStatus + ")"
				: "Keycloak 관리 권한 부족(" + path + ")";
	}
}
