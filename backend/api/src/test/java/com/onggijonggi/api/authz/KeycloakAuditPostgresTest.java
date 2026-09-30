package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : KeycloakAuditPostgresTest.java
 * Description : Keycloak 권한 변경 감사(#304)의 수집 흐름과 조회를 PostgreSQL 16으로 확인한다. 실제 Keycloak으로 만들기 어려운 상황
 *               (이벤트 목록 실패 뒤 회복, 깨진 상세, 401, 재독 창 경계)은 Keycloak 응답을 흉내 낸 클라이언트로 만든다.
 *               조회는 H2가 아니라 실제 PostgreSQL에서 커서 경계·필터·기간 경계를 본다(행 값 비교 `(evt_at, id) <`가 핵심).
 *               테스트마다 새 DB를 쓴다 — 감사 행은 지울 수 없다.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakAuditPostgresTest {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("keycloak_audit").withUsername("test").withPassword("test");

	private JdbcTemplate jdbc;
	private KeycloakAuditStore store;
	private FakeKeycloak keycloak;
	private KeycloakAuditCollector collector;
	private KeycloakAuditQueryService queries;

	@BeforeEach
	void setUp() {
		String database = "kc_audit_pg_" + SEQUENCE.incrementAndGet();
		new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl())).execute("create database " + database);
		DriverManagerDataSource dataSource = dataSource(POSTGRES.getJdbcUrl().replace("/keycloak_audit", "/" + database));
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		jdbc = new JdbcTemplate(dataSource);
		JsonMapper json = new JsonMapper();
		store = new KeycloakAuditStore(jdbc, json);
		keycloak = new FakeKeycloak();
		collector = new KeycloakAuditCollector(keycloak, new KeycloakAuditClassifier(json), store,
				new DataSourceTransactionManager(dataSource));
		queries = new KeycloakAuditQueryService(jdbc, store, json, Duration.ofMinutes(10));
	}

	// ------------------------------------------------------------------ 수집 흐름

	@Test
	void aFailedEventListIsRetriedNextCycleWithoutLosingEvents() {
		Instant at = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		keycloak.events.add(grant("u1", at));
		keycloak.eventFailures.add(serverError());

		collector.runOnce();
		KeycloakAuditStore.CollectorState failed = store.state();
		assertThat(failed.lastError()).isEqualTo("Keycloak 500");
		assertThat(failed.lastSuccessAt()).isNull();
		assertThat(kinds()).doesNotContain("ROLE_GRANTED|u1");

		collector.runOnce();
		KeycloakAuditStore.CollectorState recovered = store.state();
		assertThat(kinds()).contains("ROLE_GRANTED|u1");
		// 회복 뒤에도 마지막 오류는 남는다 — 정상 여부는 성공 시각이 오류 시각보다 늦은지로 본다.
		assertThat(recovered.lastSuccessAt()).isAfter(recovered.lastErrorAt());
		assertThat(recovered.lastError()).isEqualTo("Keycloak 500");
		assertThat(store.cursor()).contains(at);
	}

	@Test
	void anUnreadableEventIsRecordedAndTheFollowingEventsStillCollected() {
		Instant at = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		keycloak.events.add(new KeycloakAdminClient.KeycloakAdminEvent(null, at.toEpochMilli(), null, "CREATE",
				"REALM_ROLE_MAPPING", "users/u1/role-mappings/realm", "{broken"));
		keycloak.events.add(grant("u2", at.plusSeconds(1)));

		collector.runOnce();

		assertThat(store.state().lastError()).isNull();
		// 읽지 못한 이벤트는 대상을 추측하지 않고 경로만 남긴다.
		assertThat(kinds()).contains("ROLE_GRANTED|u2");
		assertThat(jdbc.queryForObject("select trg_path from keycloak_adt where evt_kind = 'EVENT_UNREADABLE'", String.class))
				.isEqualTo("users/u1/role-mappings/realm");
		assertThat(store.cursor()).contains(at.plusSeconds(1));
	}

	@Test
	void theSameFailureIsLoggedOnceAndRecoveryIsLogged() {
		Logger logger = (Logger) LoggerFactory.getLogger(KeycloakAuditCollector.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			keycloak.eventFailures.add(serverError());
			keycloak.eventFailures.add(serverError());
			collector.runOnce();
			collector.runOnce();
			collector.runOnce();
		} finally {
			logger.detachAppender(appender);
		}
		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
				"Keycloak 권한 변경 감사 수집 실패: Keycloak 500", "Keycloak 권한 변경 감사 수집이 회복됐다");
	}

	@Test
	void anUnauthorizedResponseDropsTheCachedToken() {
		keycloak.eventFailures.add(WebClientResponseException.create(401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));

		collector.runOnce();

		assertThat(keycloak.invalidated.get()).isEqualTo(1);
		assertThat(store.state().lastError()).isEqualTo("Keycloak 401");
	}

	@Test
	void theCursorNeverMovesBackward() {
		Instant later = Instant.parse("2026-09-30T10:00:00Z");
		store.advanceCursor(later, "b");
		store.advanceCursor(later.minusSeconds(60), "a");
		assertThat(store.cursor()).contains(later);
	}

	@Test
	void onlyEventsInsideTheRereadWindowAreClassifiedAgain() {
		Instant cursor = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
		keycloak.events.add(grant("first", cursor));
		collector.runOnce();

		// 커서 뒤에 늦게 보인 두 이벤트. 창(10분) 안은 다시 분류하고, 창 밖은 이미 처리한 것으로 본다.
		keycloak.events.add(grant("inside", cursor.minus(9, ChronoUnit.MINUTES)));
		keycloak.events.add(grant("outside", cursor.minus(11, ChronoUnit.MINUTES)));
		collector.runOnce();

		assertThat(kinds()).contains("ROLE_GRANTED|first", "ROLE_GRANTED|inside").doesNotContain("ROLE_GRANTED|outside");
		assertThat(store.cursor()).contains(cursor);
	}

	// ------------------------------------------------------------------ 조회

	@Test
	void cursorPagingOverEqualTimesHasNoGapsOrDuplicates() {
		Instant same = Instant.parse("2026-09-30T09:00:00Z");
		List<KeycloakAuditRow> rows = new ArrayList<>();
		for (int i = 0; i < 5; i++) rows.add(row("e" + i, KeycloakAuditEventKind.ROLE_GRANTED, same, "s" + i, "PLATFORM_ADMIN"));
		store.insert(rows);

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			KeycloakAuditPage page = page(KeycloakAuditQuery.parse(null, null, null, null, null, null, "2", cursor));
			page.items().forEach(item -> seen.add(item.targetSubject()));
			cursor = page.nextCursor();
			pages++;
		} while (cursor != null);

		assertThat(pages).isEqualTo(3);
		assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrder("s0", "s1", "s2", "s3", "s4");
	}

	@Test
	void filtersAndThePeriodBoundariesMatchTheContract() {
		Instant nine = Instant.parse("2026-09-30T09:00:00Z");
		Instant ten = Instant.parse("2026-09-30T10:00:00Z");
		store.insert(List.of(
				row("a", KeycloakAuditEventKind.ROLE_GRANTED, nine, "alice", "PLATFORM_ADMIN"),
				row("b", KeycloakAuditEventKind.ROLE_REVOKED, ten, "alice", "PLATFORM_ADMIN"),
				row("c", KeycloakAuditEventKind.ROLE_GRANTED, ten, "bob", "USER")));

		// from은 포함, to는 제외.
		assertThat(subjects(KeycloakAuditQuery.parse("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", null, null, null, null,
				null, null))).containsExactly("alice@" + nine);
		assertThat(subjects(KeycloakAuditQuery.parse(null, null, "ROLE_REVOKED", null, null, null, null, null)))
				.containsExactly("alice@" + ten);
		assertThat(subjects(KeycloakAuditQuery.parse(null, null, null, "bob", null, null, null, null)))
				.containsExactly("bob@" + ten);
		assertThat(subjects(KeycloakAuditQuery.parse(null, null, null, null, "admin-user", null, null, null))).hasSize(3);
		assertThat(subjects(KeycloakAuditQuery.parse(null, null, null, null, "nobody", null, null, null))).isEmpty();
		assertThat(subjects(KeycloakAuditQuery.parse(null, null, null, null, null, "USER", null, null)))
				.containsExactly("bob@" + ten);
	}

	@Test
	void theResponseUsesTheDocumentedFieldNames() {
		store.insert(List.of(row("a", KeycloakAuditEventKind.ROLE_GRANTED, Instant.parse("2026-09-30T09:00:00Z"), "alice",
				"PLATFORM_ADMIN")));
		store.recordSuccess(Instant.now());

		Map<?, ?> body = new JsonMapper().convertValue(page(KeycloakAuditQuery.parse(null, null, null, null, null, null, null,
				null)), Map.class);

		assertThat(keys(body)).containsExactlyInAnyOrder("items", "nextCursor", "collector");
		assertThat(keys(((List<?>) body.get("items")).get(0))).containsExactlyInAnyOrder("id", "eventKind",
				"occurredAt", "actorSubject", "actor", "targetSubject", "role", "resourcePath", "reference", "collectedAt");
		Map<?, ?> collectorState = (Map<?, ?>) body.get("collector");
		assertThat(keys(collectorState)).containsExactlyInAnyOrder("lastSuccessAt", "lastErrorAt", "lastError",
				"adminEventsEnabled", "baselineRecordedAt", "lagging");
		assertThat(collectorState.get("lagging")).isEqualTo(false);
	}

	// ------------------------------------------------------------------ 준비

	private static List<String> keys(Object map) {
		return ((Map<?, ?>) map).keySet().stream().map(String::valueOf).toList();
	}

	private KeycloakAuditPage page(KeycloakAuditQuery query) {
		return queries.page(query).block();
	}

	private List<String> subjects(KeycloakAuditQuery query) {
		return page(query).items().stream().map(item -> item.targetSubject() + "@" + item.occurredAt()).toList();
	}

	private static KeycloakAuditRow row(String eventId, KeycloakAuditEventKind kind, Instant at, String subject, String role) {
		return new KeycloakAuditRow(eventId, kind, at, "admin-user", Map.of("realmId", "master"), subject, role,
				"users/" + subject + "/role-mappings/realm", Map.of());
	}

	private List<String> kinds() {
		return jdbc.query("select evt_kind, coalesce(trg_subj, '') from keycloak_adt",
				(rs, rowNum) -> rs.getString(1) + "|" + rs.getString(2));
	}

	private static KeycloakAdminClient.KeycloakAdminEvent grant(String subject, Instant at) {
		return new KeycloakAdminClient.KeycloakAdminEvent(null, at.toEpochMilli(), null, "CREATE", "REALM_ROLE_MAPPING",
				"users/" + subject + "/role-mappings/realm", "[{\"id\":\"r1\",\"name\":\"PLATFORM_ADMIN\"}]");
	}

	private static WebClientResponseException serverError() {
		return WebClientResponseException.create(500, "Server Error", HttpHeaders.EMPTY, new byte[0], null);
	}

	private static DriverManagerDataSource dataSource(String url) {
		return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	/** 빈 realm처럼 답하고, 이벤트 목록은 넣어 둔 것을 준다. eventFailures에 넣은 예외는 이벤트 목록 호출에서 차례로 던진다. */
	private static final class FakeKeycloak extends KeycloakAdminClient {

		final List<KeycloakAdminEvent> events = new ArrayList<>();
		final Deque<RuntimeException> eventFailures = new ArrayDeque<>();
		final AtomicInteger invalidated = new AtomicInteger();

		FakeKeycloak() {
			super(WebClient.builder(), "http://unused", "fake", "fake", "fake", Duration.ZERO);
		}

		@Override
		public Mono<List<KeycloakAdminEvent>> adminEvents(LocalDate dateFrom) {
			if (!eventFailures.isEmpty()) return Mono.error(eventFailures.poll());
			return Mono.just(List.copyOf(events));
		}

		@Override
		public Mono<KeycloakEventsConfig> eventsConfig() {
			return Mono.just(new KeycloakEventsConfig(true, true));
		}

		@Override
		public Mono<Optional<String>> realmManagementUuid() {
			return Mono.just(Optional.of("realm-management-id"));
		}

		@Override
		public Mono<List<KeycloakRole>> realmRoles() {
			return Mono.just(List.of(new KeycloakRole("r1", "PLATFORM_ADMIN", false, false, "fake")));
		}

		@Override
		public Mono<List<String>> realmRoleUserIds(String role) {
			return Mono.just(List.of());
		}

		@Override
		public Mono<List<KeycloakGroup>> realmRoleGroups(String role) {
			return Mono.just(List.of());
		}

		@Override
		public Mono<List<KeycloakUser>> users() {
			return Mono.just(List.of());
		}

		@Override
		public void invalidateToken() {
			invalidated.incrementAndGet();
		}
	}
}
