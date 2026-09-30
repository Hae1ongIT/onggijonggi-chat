package com.onggijonggi.api.authz;

/**
 * Class Name : KeycloakAuditEventKind.java
 * Description : Keycloak 역할·계정 변경 감사(keycloak_adt.evt_kind, #304)의 종류. DB CHECK와 같은 목록이다.
 *               직접 부여뿐 아니라 그룹(이동 포함)·복합 역할·realm-management 관리 역할·토큰 매퍼처럼 PLATFORM_ADMIN을 얻는 간접 경로와,
 *               수집을 처음 켤 때의 보유 상태(기준선)까지 담는다.
 */
public enum KeycloakAuditEventKind {
	ROLE_GRANTED,
	ROLE_REVOKED,
	GROUP_JOINED,
	GROUP_LEFT,
	GROUP_ROLE_GRANTED,
	GROUP_ROLE_REVOKED,
	ROLE_DEFINITION_CHANGED,
	GROUP_MOVED,
	GROUP_DELETED,
	MANAGEMENT_ROLE_GRANTED,
	MANAGEMENT_ROLE_REVOKED,
	USER_CREATED_WITH_ACCESS,
	USER_ENABLED,
	USER_DISABLED,
	USER_DELETED,
	TOKEN_MAPPER_CHANGED,
	EVENT_CONFIG_CHANGED,
	ROLE_HELD_AT_START,
	BASELINE_RECORDED
}
