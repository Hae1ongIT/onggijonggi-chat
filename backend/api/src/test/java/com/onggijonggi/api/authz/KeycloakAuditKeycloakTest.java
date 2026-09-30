package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : KeycloakAuditKeycloakTest.java
 * Description : Keycloak 역할·계정 변경 감사 수집(#304)을 실제 Keycloak 26과 PostgreSQL 16으로 확인한다. 설계의 가장 큰 위험이
 *               Keycloak admin event의 모양(경로·상세·id)이라, 가짜 응답이 아니라 실제로 역할·그룹·계정을 바꿔 생긴 이벤트를 수집한다.
 *               테스트마다 새 realm과 새 DB를 쓴다 — 감사 행은 지울 수 없고 기준선은 DB당 한 번이라 테스트끼리 섞이면 안 된다.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakAuditKeycloakTest {

	private static final String CLIENT = "bff";
	private static final String SECRET = "bff-secret";
	/** 수집기가 쓰는 서비스 계정 권한(실측한 최소). view-realm은 역할 목록에 필요하다. view-clients는 모든 confidential client의
	 * secret까지 읽히는 권한이라 주지 않는다 — realm-management id와 관리 역할 보유자는 view-users로 읽는다.
	 * 이벤트를 지우는 manage-events도 주지 않는다. */
	static final List<String> COLLECTOR_ROLES = List.of("view-events", "view-users", "view-realm");
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("keycloak_audit").withUsername("test").withPassword("test");

	@Container
	static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
			.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
			.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
			.withCommand("start-dev")
			.withExposedPorts(8080)
			.waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

	@Test
	void recordsTheBaselineOnceThenDirectAndIndirectChangesIdempotently() {
		Setup setup = setup(COLLECTOR_ROLES);
		KeycloakTestAdmin admin = setup.admin();
		// 수집을 켜기 전부터 권한을 가진 사람: 직접, 그룹, 복합 역할, 관리 역할.
		String erin = admin.createUser(Map.of("username", "erin", "enabled", true));
		admin.grantRealmRole(erin, "PLATFORM_ADMIN");
		String admins = admin.createGroup("admins");
		admin.grantGroupRealmRole(admins, "PLATFORM_ADMIN");
		String frank = admin.createUser(Map.of("username", "frank", "enabled", true));
		admin.joinGroup(frank, admins);
		admin.createRole("super");
		admin.addComposite("super", "PLATFORM_ADMIN");
		String gina = admin.createUser(Map.of("username", "gina", "enabled", true));
		admin.grantRealmRole(gina, "super");
		String hana = admin.createUser(Map.of("username", "hana", "enabled", true));
		admin.grantManagementRoles(hana, List.of("manage-users"));

		setup.collector().runOnce();
		assertThat(setup.state().lastError()).isNull();
		assertThat(heldAtStart(setup.jdbc())).contains(
				"PLATFORM_ADMIN|" + erin + "|direct",
				"PLATFORM_ADMIN|" + frank + "|group",
				"PLATFORM_ADMIN|" + gina + "|composite",
				"realm-management/manage-users|" + hana + "|direct");
		assertThat(count(setup.jdbc(), "BASELINE_RECORDED")).isEqualTo(1);

		// 수집 이후의 변경.
		String alice = admin.createUser(Map.of("username", "alice", "enabled", true));
		admin.grantRealmRole(alice, "PLATFORM_ADMIN");
		admin.revokeRealmRole(alice, "PLATFORM_ADMIN");
		String bob = admin.createUser(Map.of("username", "bob", "enabled", true));
		admin.joinGroup(bob, admins);
		admin.leaveGroup(bob, admins);
		admin.grantManagementRoles(bob, List.of("manage-events"));
		String dave = admin.createUser(Map.of("username", "dave", "enabled", true, "groups", List.of("/admins")));
		admin.updateUser(bob, Map.of("enabled", false));
		admin.updateUser(bob, Map.of("enabled", true));
		admin.updateUser(alice, Map.of("firstName", "앨리스"));
		admin.createRole("auditor");
		admin.addComposite("auditor", "USER");
		admin.deleteUser(dave);
		String temp = admin.createGroup("temp");
		admin.deleteGroup(temp);
		// 리뷰로 찾은 경로들 — 실제 Keycloak에서 어떤 이벤트가 남는지 확인한 뒤 분류를 더했다.
		String outer = admin.createGroup("outer");
		admin.moveGroupUnder(admins, outer, "outer");
		admin.moveGroupToTop(outer, "outer");
		admin.createRole("viewer");
		admin.renameRole("viewer", "viewer2");
		admin.deleteRoleById(admin.roleId("viewer2"));
		admin.addClientRoleComposite(admin.clientUuid("realm-management"), "manage-realm", "PLATFORM_ADMIN");
		admin.importUserWithRole("imported", "PLATFORM_ADMIN");
		String imported = admin.userId("imported");
		admin.addHardcodedRoleMapper("bff", "PLATFORM_ADMIN");
		String sleeper = admin.createUser(Map.of("username", "sleeper", "enabled", false));
		admin.updateUser(sleeper, Map.of("enabled", true));

		setup.collector().runOnce();
		assertThat(setup.state().lastError()).isNull();
		assertThat(rows(setup.jdbc())).contains(
				"ROLE_GRANTED|" + alice + "|PLATFORM_ADMIN",
				"ROLE_REVOKED|" + alice + "|PLATFORM_ADMIN",
				"GROUP_JOINED|" + bob + "|",
				"GROUP_LEFT|" + bob + "|",
				"MANAGEMENT_ROLE_GRANTED|" + bob + "|realm-management/manage-events",
				"USER_CREATED_WITH_ACCESS|" + dave + "|",
				"USER_DISABLED|" + bob + "|",
				"USER_ENABLED|" + bob + "|",
				"ROLE_DEFINITION_CHANGED||auditor",
				"USER_DELETED|" + dave + "|",
				"GROUP_DELETED||",
				"GROUP_MOVED||",
				"ROLE_DEFINITION_CHANGED||viewer",
				"ROLE_DEFINITION_CHANGED||viewer2",
				"ROLE_DEFINITION_CHANGED||realm-management/manage-realm",
				"USER_CREATED_WITH_ACCESS|" + imported + "|PLATFORM_ADMIN",
				"TOKEN_MAPPER_CHANGED||PLATFORM_ADMIN",
				"USER_DISABLED|" + sleeper + "|",
				"USER_ENABLED|" + sleeper + "|");
		// 그룹 이동은 아래로 넣기와 최상위로 빼기 두 번이다. roles-by-id 삭제에도 저장해 둔 이름이 붙는다.
		assertThat(count(setup.jdbc(), "GROUP_MOVED")).isEqualTo(2);
		assertThat(setup.jdbc().queryForObject("select role from keycloak_adt where evt_kind = 'ROLE_DEFINITION_CHANGED'"
				+ " and trg_ref->>'operation' = 'DELETE'", String.class)).isEqualTo("viewer2");
		// 이름 변경은 권한과 무관하다. 활성 상태가 그대로인 사용자 수정은 남지 않는다.
		assertThat(rows(setup.jdbc())).noneMatch(row -> row.startsWith("USER_ENABLED|" + alice)
				|| row.startsWith("USER_DISABLED|" + alice));

		// 같은 구간을 다시 읽어도 늘지 않는다. 기준선도 다시 잡지 않는다.
		int before = total(setup.jdbc());
		setup.collector().runOnce();
		assertThat(total(setup.jdbc())).isEqualTo(before);
		assertThat(count(setup.jdbc(), "BASELINE_RECORDED")).isEqualTo(1);
		// 상세 원문과 IP 주소는 저장하지 않는다.
		assertThat(setup.jdbc().queryForObject("select count(*) from keycloak_adt where act_json::text like '%ipAddress%'"
				+ " or trg_ref::text like '%firstName%'", Integer.class)).isZero();
	}

	@Test
	void theBaselineCountsEffectiveHoldersDisabledAccountsAndSurvivesAMissingRole() {
		Setup setup = setup(COLLECTOR_ROLES);
		KeycloakTestAdmin admin = setup.admin();
		// 기본 역할도 realm 복합 역할이라 경로별 조회(composite)로 잡힌다.
		admin.addDefaultRole("PLATFORM_ADMIN");
		String ivy = admin.createUser(Map.of("username", "ivy", "enabled", true));
		// 클라이언트 복합 역할 경유는 경로별 조회로 보이지 않는다 — 실효 역할 보강(effective)이 잡는다.
		admin.removeDefaultRole("PLATFORM_ADMIN");
		admin.addClientRoleComposite(admin.clientUuid("realm-management"), "manage-realm", "PLATFORM_ADMIN");
		String kim = admin.createUser(Map.of("username", "kim", "enabled", true));
		admin.grantManagementRoles(kim, List.of("manage-realm"));
		String paused = admin.createUser(Map.of("username", "paused", "enabled", false));

		setup.collector().runOnce();

		assertThat(setup.state().lastError()).isNull();
		assertThat(heldAtStart(setup.jdbc())).contains("PLATFORM_ADMIN|" + kim + "|effective",
				"realm-management/manage-realm|" + kim + "|direct").doesNotContain("PLATFORM_ADMIN|" + ivy + "|effective");
		// 수집 전부터 비활성이던 계정은 기준선에 비활성으로 남는다. 그래서 나중에 켜면 기록된다.
		assertThat(rows(setup.jdbc())).contains("USER_DISABLED|" + paused + "|");
		admin.updateUser(paused, Map.of("enabled", true));
		setup.collector().runOnce();
		assertThat(rows(setup.jdbc())).contains("USER_ENABLED|" + paused + "|");
	}

	@Test
	void rereadingTheSameEventsNeverInventsAnEnableAndOversizedValuesDoNotStopCollection() {
		Setup setup = setup(COLLECTOR_ROLES);
		KeycloakTestAdmin admin = setup.admin();
		setup.collector().runOnce();
		// 프로필 저장(활성 그대로) 뒤 곧바로 비활성화. 두 이벤트가 재독 창 안에 있어 다음 주기에도 다시 분류된다.
		String carl = admin.createUser(Map.of("username", "carl", "enabled", true));
		admin.updateUser(carl, Map.of("enabled", true, "firstName", "칼"));
		admin.updateUser(carl, Map.of("enabled", false));
		// 관리자가 300자 역할 이름을 가진 매퍼를 만들어도 수집이 멈추지 않는다.
		admin.addHardcodedRoleMapper("bff", "long-role", "R".repeat(300));

		setup.collector().runOnce();
		setup.collector().runOnce();

		assertThat(setup.state().lastError()).isNull();
		assertThat(rows(setup.jdbc())).filteredOn(row -> row.contains(carl)).containsExactly("USER_DISABLED|" + carl + "|");
		assertThat(setup.jdbc().queryForObject("select length(role) from keycloak_adt where evt_kind = 'TOKEN_MAPPER_CHANGED'"
				+ " and trg_ref ? 'roleTruncated'", Integer.class)).isEqualTo(255);
		// 그 뒤의 변경도 계속 수집된다.
		admin.updateUser(carl, Map.of("enabled", true));
		setup.collector().runOnce();
		assertThat(rows(setup.jdbc())).contains("USER_ENABLED|" + carl + "|");
	}

	@Test
	void aRealmWithoutThePlatformAdminRoleStillRecordsTheBaselineAndCollects() {
		Setup setup = setup(COLLECTOR_ROLES);
		setup.admin().deleteRole("PLATFORM_ADMIN");

		setup.collector().runOnce();

		assertThat(setup.state().lastError()).isNull();
		assertThat(count(setup.jdbc(), "BASELINE_RECORDED")).isEqualTo(1);
		// realm 전체 수정으로 admin event를 끄는 것도 이벤트로 남는다(끈 뒤의 변경은 남지 않는다).
		setup.admin().updateRealm(Map.of("adminEventsEnabled", false));
		setup.collector().runOnce();
		assertThat(setup.jdbc().queryForObject("select count(*) from keycloak_adt where evt_kind = 'EVENT_CONFIG_CHANGED'"
				+ " and trg_path = 'realm'", Integer.class)).isEqualTo(1);
	}

	@Test
	void turningAdminEventsOffIsRecordedAndShownInTheCollectorState() {
		Setup setup = setup(COLLECTOR_ROLES);
		setup.collector().runOnce();
		setup.admin().setAdminEvents(false);

		setup.collector().runOnce();

		assertThat(count(setup.jdbc(), "EVENT_CONFIG_CHANGED")).isGreaterThanOrEqualTo(1);
		KeycloakAuditPage page = setup.queries().page(KeycloakAuditQuery.parse(null, null, null, null, null, null, null, null))
				.block();
		assertThat(page.collector().adminEventsEnabled()).isFalse();
		assertThat(page.collector().lagging()).isFalse();
		assertThat(page.collector().baselineRecordedAt()).isNotNull();
	}

	@Test
	void aServiceAccountWithoutEventAccessFailsQuietlyIntoTheState() {
		Setup setup = setup(List.of("view-users"));

		setup.collector().runOnce();

		KeycloakAuditStore.CollectorState state = setup.state();
		assertThat(state.lastError()).startsWith("Keycloak 403").contains("/events/config");
		assertThat(state.lastSuccessAt()).isNull();
		assertThat(total(setup.jdbc())).isZero();
		KeycloakAuditPage page = setup.queries().page(KeycloakAuditQuery.parse(null, null, null, null, null, null, null, null))
				.block();
		assertThat(page.collector().lagging()).isTrue();
	}

	@Test
	void aCursorPushedIntoTheFutureIsReportedInsteadOfSilentlySkippingEvents() {
		Setup setup = setup(COLLECTOR_ROLES);
		setup.collector().runOnce();
		setup.jdbc().update("update keycloak_adt_crs set evt_at = now() + interval '1 day' where id = 1");

		setup.collector().runOnce();

		assertThat(setup.state().lastError()).contains("미래");
	}

	@Test
	void auditRowsCannotBeChangedOrRemoved() {
		Setup setup = setup(COLLECTOR_ROLES);
		setup.collector().runOnce();
		JdbcTemplate jdbc = setup.jdbc();

		assertThatThrownBy(() -> jdbc.update("update keycloak_adt set role = 'x'")).hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("delete from keycloak_adt")).hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.execute("truncate keycloak_adt")).hasMessageContaining("append-only");
		assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid = 'keycloak_adt'::regclass"
				+ " and not tgisinternal and tgenabled = 'A'", Integer.class)).isEqualTo(2);
	}

	// ------------------------------------------------------------------ 준비

	private record Setup(KeycloakTestAdmin admin, KeycloakAuditCollector collector, KeycloakAuditStore store,
			KeycloakAuditQueryService queries, JdbcTemplate jdbc) {
		KeycloakAuditStore.CollectorState state() {
			return store.state();
		}
	}

	/** 새 realm·새 DB. 서비스 계정에는 주어진 realm-management 역할만 준다. */
	private static Setup setup(List<String> serviceAccountRoles) {
		int number = SEQUENCE.incrementAndGet();
		String realm = "audit-" + number + "-" + UUID.randomUUID().toString().substring(0, 6);
		String baseUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
		KeycloakTestAdmin admin = new KeycloakTestAdmin(baseUrl, realm);
		admin.createRealm(CLIENT, SECRET);
		admin.grantServiceAccount(CLIENT, serviceAccountRoles);

		JdbcTemplate root = new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl()));
		String database = "kc_audit_" + number;
		root.execute("create database " + database);
		String url = POSTGRES.getJdbcUrl().replace("/keycloak_audit", "/" + database);
		DriverManagerDataSource dataSource = dataSource(url);
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		JsonMapper json = new JsonMapper();
		KeycloakAuditStore store = new KeycloakAuditStore(jdbc, json);
		KeycloakAdminClient client = new KeycloakAdminClient(WebClient.builder(), baseUrl, realm, CLIENT, SECRET, Duration.ZERO);
		KeycloakAuditCollector collector = new KeycloakAuditCollector(client, new KeycloakAuditClassifier(json), store,
				new DataSourceTransactionManager(dataSource));
		KeycloakAuditQueryService queries = new KeycloakAuditQueryService(jdbc, store, json, Duration.ofMinutes(10));
		return new Setup(admin, collector, store, queries, jdbc);
	}

	private static DriverManagerDataSource dataSource(String url) {
		return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	/** "종류|대상|역할" 목록. 역할·대상이 없으면 빈 칸. */
	private static List<String> rows(JdbcTemplate jdbc) {
		return jdbc.query("select evt_kind, coalesce(trg_subj, ''), coalesce(role, '') from keycloak_adt",
				(rs, rowNum) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
	}

	/** 기준선 행을 "역할|보유자|경로 종류"로. */
	private static List<String> heldAtStart(JdbcTemplate jdbc) {
		return jdbc.query("select role, trg_subj, trg_ref->>'via' from keycloak_adt where evt_kind = 'ROLE_HELD_AT_START'",
				(rs, rowNum) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
	}

	private static int count(JdbcTemplate jdbc, String kind) {
		return jdbc.queryForObject("select count(*) from keycloak_adt where evt_kind = ?", Integer.class, kind);
	}

	private static int total(JdbcTemplate jdbc) {
		return jdbc.queryForObject("select count(*) from keycloak_adt", Integer.class);
	}
}
