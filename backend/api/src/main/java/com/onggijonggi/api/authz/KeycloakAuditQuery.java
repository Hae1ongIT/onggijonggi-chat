package com.onggijonggi.api.authz;

import java.time.Instant;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : KeycloakAuditQuery.java
 * Description : Keycloak 권한 변경 감사 조회의 필터·페이지(#304). #259의 AuthorizationAuditQuery와 같은 규칙이다 — 기간은
 *               `from <= evt_at < to`, 틀린 값은 모두 400, 커서는 같은 형식((시각, id))을 쓴다. Keycloak id 필터는 열 길이 안이어야 한다.
 */
public record KeycloakAuditQuery(Instant from, Instant to, KeycloakAuditEventKind eventKind, String targetSubject,
		String actorSubject, String role, int limit, AuthorizationAuditCursor cursor) {

	private static final int MAX_TEXT = 255;

	public static KeycloakAuditQuery parse(String from, String to, String eventKind, String targetSubject,
			String actorSubject, String role, String limit, String cursor) {
		Instant parsedFrom = optional(from, AuthorizationAuditQuery::instant, "from");
		Instant parsedTo = optional(to, AuthorizationAuditQuery::instant, "to");
		if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) throw badRequest("from은 to보다 앞이어야 한다");
		int parsedLimit = limit == null ? AuthorizationAuditQuery.DEFAULT_LIMIT : optional(limit, Integer::parseInt, "limit");
		if (parsedLimit < 1 || parsedLimit > AuthorizationAuditQuery.MAX_LIMIT) {
			throw badRequest("limit은 1~" + AuthorizationAuditQuery.MAX_LIMIT + "이다");
		}
		return new KeycloakAuditQuery(parsedFrom, parsedTo,
				optional(eventKind, KeycloakAuditEventKind::valueOf, "eventKind"),
				text(targetSubject, "targetSubject"), text(actorSubject, "actorSubject"), text(role, "role"), parsedLimit,
				cursor == null ? null : AuthorizationAuditCursor.decode(cursor));
	}

	private static String text(String value, String name) {
		if (value == null) return null;
		if (value.isBlank() || value.length() > MAX_TEXT) throw badRequest("잘못된 " + name + " 값이다");
		return value;
	}

	private static <T> T optional(String value, Function<String, T> parser, String name) {
		if (value == null) return null;
		try {
			return parser.apply(value);
		} catch (RuntimeException invalid) {
			throw badRequest("잘못된 " + name + " 값이다");
		}
	}

	private static ResponseStatusException badRequest(String reason) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
	}
}
