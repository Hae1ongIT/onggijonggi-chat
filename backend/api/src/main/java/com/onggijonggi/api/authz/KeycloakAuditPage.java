package com.onggijonggi.api.authz;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Class Name : KeycloakAuditPage.java
 * Description : Keycloak 권한 변경 감사 조회 응답(#304). 한 페이지의 행과 다음 커서, 그리고 수집 상태를 함께 준다 —
 *               감사를 보는 자리에서 기록이 끊겼는지(수집 실패·admin event 꺼짐·지연)를 바로 알 수 있게 하려는 것이다.
 */
public record KeycloakAuditPage(List<Item> items, String nextCursor, Collector collector) {

	/** 표시 이름은 조회하지 않는다 — 삭제된 계정도 있으므로 Keycloak id를 그대로 보인다. */
	public record Item(UUID id, KeycloakAuditEventKind eventKind, Instant occurredAt, String actorSubject,
			Map<String, Object> actor, String targetSubject, String role, String resourcePath, Map<String, Object> reference,
			Instant collectedAt) {
	}

	/**
	 * @param adminEventsEnabled 마지막으로 읽은 Keycloak 설정 기준. 한 번도 읽지 못했으면 null
	 * @param lagging 마지막 성공이 기준 시간보다 오래됐거나 한 번도 성공하지 않았다
	 */
	public record Collector(Instant lastSuccessAt, Instant lastErrorAt, String lastError, Boolean adminEventsEnabled,
			Instant baselineRecordedAt, boolean lagging) {
	}
}
