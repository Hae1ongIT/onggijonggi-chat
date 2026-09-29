package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : CutoverMigrationPostgresTest.java
 * Description : PostgreSQL 16에서 절체 전 데이터를 만든 뒤 #262 migration의 성공·중단·제약을 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class CutoverMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("cutover_test").withUsername("test").withPassword("test");

	@BeforeEach
	void clean() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).load().clean();
	}

	@Test
	void emptyDatabaseWithoutTenantAcceptsCompleteMigration() throws SQLException {
		migrateAll();
		try (Connection connection = connect()) {
			assertThat(count(connection, "select count(*) from ctv")).isZero();
			assertThat(count(connection, "select count(*) from flyway_schema_history where success = false")).isZero();
		}
	}

	@Test
	void preservesCollabPlacementAndBackfillsDirectAndChildren() throws SQLException {
		migrateBeforeCutover();
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		UUID owner = UUID.randomUUID();
		UUID direct = UUID.randomUUID();
		UUID collab = UUID.randomUUID();
		UUID message = UUID.randomUUID();
		try (Connection connection = connect()) {
			seedTenant(connection, tenant, root, common, team);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'cutover-owner')", owner);
			execute(connection, "insert into thr (id, kind, drc_own_user_id, created_user_id, title) values (?, 'DIRECT', ?, ?, 'direct')", direct, owner, owner);
			execute(connection, "insert into thr (id, kind, created_user_id, title, tnn_id, wrk_node_id) values (?, 'COLLAB', ?, 'collab', ?, ?)", collab, owner, tenant, team);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status, content, completed_at) values (?, ?, 0, 'SYSTEM', 'COMPLETE', 'original', now())", message, direct);
		}
		migrateAll();
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", direct)).isEqualTo(tenant);
			assertThat(uuid(connection, "select wrk_node_id from thr where id = ?", direct)).isEqualTo(common);
			assertThat(uuid(connection, "select wrk_node_id from thr where id = ?", collab)).isEqualTo(team);
			assertThat(uuid(connection, "select tnn_id from msg where id = ?", message)).isEqualTo(tenant);
			assertThat(count(connection, "select count(*) from msg where id = ? and content = 'original'", message)).isEqualTo(1);
			assertThatThrownBy(() -> execute(connection, "update msg set content = 'changed' where id = ?", message))
					.isInstanceOf(SQLException.class);
			assertThat(count(connection, "select count(*) from ctv")).isZero();
			assertThatThrownBy(() -> execute(connection,
					"insert into msg (id, thr_id, tnn_id, seq, ath_kind, status, content) values (?, ?, ?, 1, 'SYSTEM', 'COMPLETE', 'wrong tenant')",
					UUID.randomUUID(), direct, UUID.randomUUID())).isInstanceOf(SQLException.class);
			execute(connection, "insert into ctv (id, tnn_id) values (1, ?)", tenant);
			assertThatThrownBy(() -> execute(connection, "update ctv set completed_at = now() where id = 1"))
					.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(connection, "delete from ctv where id = 1"))
					.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(connection, "truncate ctv"))
					.isInstanceOf(SQLException.class);
			assertThat(count(connection, "select count(*) from ctv")).isEqualTo(1);
		}
	}

	@Test
	void existingThreadWithoutActiveTenantAbortsWithoutPartialBackfill() throws SQLException {
		migrateBeforeCutover();
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		try (Connection connection = connect()) {
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'orphan-owner')", user);
			execute(connection, "insert into thr (id, kind, drc_own_user_id, created_user_id, title) values (?, 'DIRECT', ?, ?, 'legacy')", thread, user, user);
		}
		assertThatThrownBy(this::migrateAll).hasMessageContaining("exactly one ACTIVE tenant");
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", thread)).isNull();
		}
	}

	@Test
	void invalidCollabPlacementAbortsBeforeBackfill() throws SQLException {
		migrateBeforeCutover();
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		try (Connection connection = connect()) {
			seedTenant(connection, tenant, root, common, team);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'collab-author')", user);
			execute(connection, "insert into thr (id, kind, created_user_id, title, wrk_node_id) values (?, 'COLLAB', ?, 'invalid', ?)", thread, user, root);
		}
		assertThatThrownBy(this::migrateAll).hasMessageContaining("invalid workspace placement");
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", thread)).isNull();
		}
	}

	private void migrateBeforeCutover() {
		flyway().target("20260928080757930").load().migrate();
	}

	private void migrateAll() {
		Flyway flyway = flyway().load();
		flyway.migrate();
		flyway.validate();
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration");
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static void seedTenant(Connection connection, UUID tenant, UUID root, UUID common, UUID team) throws SQLException {
		execute(connection, "insert into tnn (id, tnn_key, name) values (?, 'cutover', 'Cutover')", tenant);
		execute(connection, "insert into wrk_node (id, tnn_id, node_key, kind, name, path) values (?, ?, 'root', 'ROOT', 'Root', array[?]::uuid[])", root, tenant, root);
		execute(connection, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'common', 'COMMON', 'Common', array[?, ?]::uuid[])", common, tenant, root, root, common);
		execute(connection, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'team', 'WORK', 'Team', array[?, ?]::uuid[])", team, tenant, root, root, team);
	}

	private static void execute(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			statement.executeUpdate();
		}
	}

	private static int count(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	private static UUID uuid(Connection connection, String sql, UUID id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getObject(1, UUID.class);
			}
		}
	}
}
