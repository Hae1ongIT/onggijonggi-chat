package com.onggijonggi.api.authz;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : KeycloakAuditStore.java
 * Description : keycloak_adt(append-only)와 수집 상태 keycloak_adt_crs(단일 행)를 다룬다(#304). 행 추가는 멱등 키에 걸리면
 *               조용히 건너뛴다 — 날짜 단위로 다시 읽은 이벤트가 중복으로 들어오는 것이 정상 흐름이다.
 *               트랜잭션 경계는 호출하는 수집기가 정한다. JDBC가 블로킹이라 스케줄 스레드에서만 부른다.
 */
@Component
public class KeycloakAuditStore {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public KeycloakAuditStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	/**
	 * 새로 들어간 행 수. 이미 있는 행(멱등 키·기준선 표지 unique)은 세지 않는다.
	 * 글자 열은 열 길이에 맞춰 자른다 — 관리자가 만든 긴 역할·매퍼 이름 하나 때문에 저장이 실패해 커서가 멈추고 이후 감사가
	 * 모두 끊기면 안 된다. 잘랐으면 reference에 원문의 해시를 남긴다(원문은 Keycloak 쪽에 있다).
	 */
	public int insert(List<KeycloakAuditRow> rows) {
		int inserted = 0;
		for (KeycloakAuditRow original : rows) {
			KeycloakAuditRow row = fitted(original);
			inserted += jdbc.update("""
					insert into keycloak_adt (id, keycloak_evt_id, evt_kind, evt_at, act_subj, act_json, trg_subj, role,
					                          trg_path, trg_ref)
					values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
					on conflict do nothing
					""", UUID.randomUUID(), row.keycloakEventId(), row.kind().name(), Timestamp.from(row.occurredAt()),
					row.actorSubject(), json(row.actor()), row.targetSubject(), row.role(), row.targetPath(),
					json(row.reference()));
		}
		return inserted;
	}

	private static final int TEXT = 255;
	private static final int PATH = 1024;

	static KeycloakAuditRow fitted(KeycloakAuditRow row) {
		Map<String, Object> reference = scalars(row.reference(), 0);
		String role = fit(row.role(), TEXT, "role", reference);
		String targetPath = fit(row.targetPath(), PATH, "targetPath", reference);
		String targetSubject = fit(row.targetSubject(), TEXT, "targetSubject", reference);
		String actorSubject = fit(row.actorSubject(), TEXT, "actorSubject", reference);
		String eventId = fit(row.keycloakEventId(), TEXT, "keycloakEventId", reference);
		// 표시값을 잘라도 원래 unique tuple은 구분한다. 해시는 응답에 포함되지 않는 이벤트 키에만 쓴다.
		if (oversized(row.keycloakEventId(), TEXT) || oversized(row.targetSubject(), TEXT)
				|| oversized(row.role(), TEXT) || oversized(row.targetPath(), PATH)) {
			eventId = "fitted:" + sha256(keyPart(row.keycloakEventId()) + keyPart(row.targetSubject())
					+ keyPart(row.targetPath()) + keyPart(row.role()));
		}
		return new KeycloakAuditRow(eventId, row.kind(), row.occurredAt(), actorSubject, row.actor(), targetSubject, role,
				targetPath, reference);
	}

	private static boolean oversized(String value, int limit) {
		return value != null && value.length() > limit;
	}

	/** null·빈 문자열·구분 문자를 구별해 원래 unique tuple을 모호성 없이 연결한다. */
	private static String keyPart(String value) {
		return value == null ? "-1:" : value.length() + ":" + value;
	}

	/**
	 * reference에는 문자열(255자까지)·불리언·숫자와, 그것들을 담은 한두 단계의 목록·맵만 남긴다. 그 밖의 값은 문자열로 바꾼다 —
	 * 조작된 상세가 깊거나 큰 JSON을 넣어 조회가 파싱에 실패하면 그 행이 든 페이지 전체가 막힌다(지울 수도 없다).
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> scalars(Map<String, Object> source, int depth) {
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		source.forEach((key, value) -> result.put(key, scalar(value, depth)));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Object scalar(Object value, int depth) {
		if (value == null || value instanceof Boolean || value instanceof Number) return value;
		if (value instanceof String text) return text.length() > TEXT ? text.substring(0, TEXT) : text;
		if (depth < 2 && value instanceof Map<?, ?> map) return scalars((Map<String, Object>) map, depth + 1);
		if (depth < 2 && value instanceof List<?> list) {
			return list.stream().limit(100).map(item -> scalar(item, depth + 1)).toList();
		}
		String text = String.valueOf(value);
		return text.length() > TEXT ? text.substring(0, TEXT) : text;
	}

	private static String fit(String value, int limit, String name, Map<String, Object> reference) {
		if (value == null || value.length() <= limit) return value;
		reference.put(name + "Truncated", sha256(value));
		return value.substring(0, limit);
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	public boolean baselineRecorded() {
		Integer count = jdbc.queryForObject("select count(*) from keycloak_adt where evt_kind = 'BASELINE_RECORDED'",
				Integer.class);
		return count != null && count > 0;
	}

	public Optional<Instant> baselineRecordedAt() {
		return jdbc.query("select evt_at from keycloak_adt where evt_kind = 'BASELINE_RECORDED'",
				(rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant()).stream().findFirst();
	}

	/** 계정의, 주어진 시각 이전에 마지막으로 기록된 활성 상태와 그 시각. 다시 읽어도 결과가 같도록 시각을 기준으로 한다. */
	public Optional<EnabledAt> enabledBefore(String subject, Instant before) {
		return jdbc.query("""
				select evt_kind, evt_at from keycloak_adt
				where trg_subj = ? and evt_kind in ('USER_ENABLED', 'USER_DISABLED') and evt_at < ?
				order by evt_at desc, created_at desc limit 1
				""", (rs, rowNum) -> new EnabledAt("USER_ENABLED".equals(rs.getString(1)),
				rs.getObject(2, OffsetDateTime.class).toInstant()), subject, Timestamp.from(before)).stream().findFirst();
	}

	public record EnabledAt(boolean enabled, Instant at) {
	}

	/** 마지막으로 처리한 이벤트 시각. 한 번도 읽지 않았으면 빈 Optional. */
	public Optional<Instant> cursor() {
		return Optional.ofNullable(jdbc.queryForObject("select evt_at from keycloak_adt_crs where id = 1",
				(rs, rowNum) -> {
					OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
					return at == null ? null : at.toInstant();
				}));
	}

	/** 커서는 뒤로 가지 않는다(최신 이벤트가 사라져도 다시 읽는 범위만 늘지 않게). */
	public void advanceCursor(Instant at, String keycloakEventId) {
		jdbc.update("""
				update keycloak_adt_crs set keycloak_evt_id = case when evt_at is null or evt_at <= ? then ? else keycloak_evt_id end,
				       evt_at = greatest(coalesce(evt_at, ?), ?)
				where id = 1
				""", Timestamp.from(at), keycloakEventId, Timestamp.from(at), Timestamp.from(at));
	}

	/** 마지막으로 읽은 Keycloak 이벤트 설정. 처음이면 빈 Optional. */
	public Optional<Map<String, Object>> lastEventsConfig() {
		String value = jdbc.queryForObject("select evt_cnf_json::text from keycloak_adt_crs where id = 1", String.class);
		return Optional.ofNullable(value).map(this::map);
	}

	/** 마지막으로 저장한 realm 역할 id→이름. roles-by-id 이벤트의 역할 이름을 되찾는 데 쓴다. */
	public Map<String, String> roleNames() {
		String value = jdbc.queryForObject("select role_ref_json::text from keycloak_adt_crs where id = 1", String.class);
		Map<String, String> names = new java.util.HashMap<>();
		if (value != null) map(value).forEach((id, name) -> names.put(id, String.valueOf(name)));
		return names;
	}

	public void recordRoleNames(Map<String, String> names) {
		jdbc.update("update keycloak_adt_crs set role_ref_json = ?::jsonb where id = 1", objectMapper.writeValueAsString(names));
	}

	/** 재독 구간의 역할 이름 변경을 역순으로 읽어, 구간 시작 전 이름 표를 복원한다. */
	public List<RoleRename> roleRenamesSince(Instant floor) {
		return jdbc.query("""
				select trg_ref->>'roleId', trg_ref->>'before', trg_ref->>'after', evt_at, keycloak_evt_id
				from keycloak_adt
				where evt_kind = 'ROLE_DEFINITION_CHANGED' and trg_ref->>'operation' = 'RENAME' and evt_at >= ?
				order by evt_at desc, keycloak_evt_id desc
				""", (rs, rowNum) -> new RoleRename(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getString(5)), Timestamp.from(floor));
	}

	public record RoleRename(String roleId, String before, String after, Instant at, String eventId) {
	}

	public void recordEventsConfig(Map<String, Object> config) {
		jdbc.update("update keycloak_adt_crs set evt_cnf_json = ?::jsonb where id = 1", json(config));
	}

	public void recordSuccess(Instant at) {
		jdbc.update("update keycloak_adt_crs set last_run_at = ? where id = 1", Timestamp.from(at));
	}

	/** 오류 요지만 남긴다. 호출부가 토큰·응답 원문을 넣지 않은 짧은 문장을 준다. */
	public void recordFailure(Instant at, String summary) {
		String text = summary.length() > 500 ? summary.substring(0, 500) : summary;
		jdbc.update("update keycloak_adt_crs set last_err_at = ?, err_text = ? where id = 1", Timestamp.from(at), text);
	}

	/** 조회 응답의 수집 상태에 쓰는 값. */
	public CollectorState state() {
		return jdbc.queryForObject("""
				select last_run_at, last_err_at, err_text, evt_cnf_json::text from keycloak_adt_crs where id = 1
				""", (rs, rowNum) -> new CollectorState(instant(rs.getObject(1, OffsetDateTime.class)),
				instant(rs.getObject(2, OffsetDateTime.class)), rs.getString(3),
				rs.getString(4) == null ? null : map(rs.getString(4))));
	}

	public record CollectorState(Instant lastSuccessAt, Instant lastErrorAt, String lastError,
			Map<String, Object> eventsConfig) {
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private String json(Map<String, Object> value) {
		return objectMapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(String json) {
		return objectMapper.readValue(json, Map.class);
	}
}
