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

	/** 새로 들어간 행 수. 이미 있는 행(멱등 키·기준선 표지 unique)은 세지 않는다. */
	public int insert(List<KeycloakAuditRow> rows) {
		int inserted = 0;
		for (KeycloakAuditRow row : rows) {
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

	public boolean baselineRecorded() {
		Integer count = jdbc.queryForObject("select count(*) from keycloak_adt where evt_kind = 'BASELINE_RECORDED'",
				Integer.class);
		return count != null && count > 0;
	}

	public Optional<Instant> baselineRecordedAt() {
		return jdbc.query("select evt_at from keycloak_adt where evt_kind = 'BASELINE_RECORDED'",
				(rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant()).stream().findFirst();
	}

	/** 사용자의 마지막으로 기록된 활성 상태. 비활성화·재활성화만 비교하려고 쓴다. */
	public Optional<Boolean> lastKnownEnabled(String subject) {
		return jdbc.query("""
				select evt_kind from keycloak_adt where trg_subj = ? and evt_kind in ('USER_ENABLED', 'USER_DISABLED')
				order by evt_at desc, created_at desc limit 1
				""", (rs, rowNum) -> "USER_ENABLED".equals(rs.getString(1)), subject).stream().findFirst();
	}

	/** 마지막으로 처리한 이벤트 시각. 한 번도 읽지 않았으면 빈 Optional. */
	public Optional<Instant> cursor() {
		return Optional.ofNullable(jdbc.queryForObject("select evt_at from keycloak_adt_crs where id = 1",
				(rs, rowNum) -> {
					OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
					return at == null ? null : at.toInstant();
				}));
	}

	public void advanceCursor(Instant at, String keycloakEventId) {
		jdbc.update("update keycloak_adt_crs set evt_at = ?, keycloak_evt_id = ? where id = 1",
				Timestamp.from(at), keycloakEventId);
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
