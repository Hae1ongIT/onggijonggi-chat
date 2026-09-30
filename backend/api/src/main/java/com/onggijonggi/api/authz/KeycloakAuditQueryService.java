package com.onggijonggi.api.authz;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : KeycloakAuditQueryService.java
 * Description : Keycloak 권한 변경 감사(keycloak_adt) 조회(#304). control plane 전용이라 권한 판정은 보안 설정
 *               (`/api/platform/**` → PLATFORM_ADMIN)이 한다. 정렬은 (evt_at desc, id desc), 한 행을 더 읽어 다음 페이지를 본다.
 *               응답에 수집 상태를 함께 담는다. JDBC가 블로킹이라 boundedElastic에서 돈다.
 */
@Service
public class KeycloakAuditQueryService {

	private static final String COLUMNS = "id, evt_kind, evt_at, act_subj, act_json::text as act_json, trg_subj, role,"
			+ " trg_path, trg_ref::text as trg_ref, created_at";

	private final NamedParameterJdbcTemplate jdbc;
	private final KeycloakAuditStore store;
	private final ObjectMapper objectMapper;
	private final Duration lagWarning;

	public KeycloakAuditQueryService(JdbcTemplate jdbcTemplate, KeycloakAuditStore store, ObjectMapper objectMapper,
			@Value("${app.keycloak.audit.lag-warning:10m}") Duration lagWarning) {
		this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.store = store;
		this.objectMapper = objectMapper;
		this.lagWarning = lagWarning;
	}

	public Mono<KeycloakAuditPage> page(KeycloakAuditQuery query) {
		return Mono.fromCallable(() -> pageBlocking(query)).subscribeOn(Schedulers.boundedElastic());
	}

	private KeycloakAuditPage pageBlocking(KeycloakAuditQuery query) {
		StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from keycloak_adt where true");
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (query.from() != null) condition(sql, params, "evt_at >= :from", "from", utc(query.from()));
		if (query.to() != null) condition(sql, params, "evt_at < :to", "to", utc(query.to()));
		if (query.eventKind() != null) condition(sql, params, "evt_kind = :eventKind", "eventKind", query.eventKind().name());
		if (query.targetSubject() != null) condition(sql, params, "trg_subj = :target", "target", query.targetSubject());
		if (query.actorSubject() != null) condition(sql, params, "act_subj = :actor", "actor", query.actorSubject());
		if (query.role() != null) condition(sql, params, "role = :role", "role", query.role());
		if (query.cursor() != null) {
			sql.append(" and (evt_at, id) < (:cursorAt, :cursorId)");
			params.addValue("cursorAt", utc(query.cursor().createdAt())).addValue("cursorId", query.cursor().id());
		}
		sql.append(" order by evt_at desc, id desc limit :fetch");
		params.addValue("fetch", query.limit() + 1);

		List<KeycloakAuditPage.Item> rows = new ArrayList<>(jdbc.query(sql.toString(), params, (rs, rowNum) -> item(rs)));
		String next = null;
		if (rows.size() > query.limit()) {
			rows.remove(rows.size() - 1);
			KeycloakAuditPage.Item last = rows.get(rows.size() - 1);
			next = new AuthorizationAuditCursor(last.occurredAt(), last.id()).encode();
		}
		return new KeycloakAuditPage(rows, next, collector(Instant.now()));
	}

	private KeycloakAuditPage.Collector collector(Instant now) {
		KeycloakAuditStore.CollectorState state = store.state();
		Object enabled = state.eventsConfig() == null ? null : state.eventsConfig().get("adminEventsEnabled");
		boolean lagging = state.lastSuccessAt() == null || state.lastSuccessAt().isBefore(now.minus(lagWarning));
		return new KeycloakAuditPage.Collector(state.lastSuccessAt(), state.lastErrorAt(), state.lastError(),
				enabled instanceof Boolean flag ? flag : null, store.baselineRecordedAt().orElse(null), lagging);
	}

	private static void condition(StringBuilder sql, MapSqlParameterSource params, String clause, String name, Object value) {
		sql.append(" and ").append(clause);
		params.addValue(name, value);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private KeycloakAuditPage.Item item(ResultSet rs) throws SQLException {
		return new KeycloakAuditPage.Item(rs.getObject("id", UUID.class),
				KeycloakAuditEventKind.valueOf(rs.getString("evt_kind")), rs.getObject("evt_at", OffsetDateTime.class).toInstant(),
				rs.getString("act_subj"), map(rs.getString("act_json")), rs.getString("trg_subj"), rs.getString("role"),
				rs.getString("trg_path"), map(rs.getString("trg_ref")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(String json) {
		return json == null ? null : objectMapper.readValue(json, Map.class);
	}
}
