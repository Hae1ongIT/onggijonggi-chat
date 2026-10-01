package com.onggijonggi.api.authz;

import java.time.Instant;
import java.util.Map;

/**
 * Class Name : KeycloakAuditRow.java
 * Description : keycloak_adt에 넣을 한 행(#304). actor·reference는 jsonb로 저장한다. 멱등 키는
 *               (keycloakEventId, targetSubject, targetPath, role)이다 — 한 이벤트의 여러 행은 role·targetPath로 갈린다.
 */
public record KeycloakAuditRow(String keycloakEventId, KeycloakAuditEventKind kind, Instant occurredAt, String actorSubject,
		Map<String, Object> actor, String targetSubject, String role, String targetPath, Map<String, Object> reference) {
}
