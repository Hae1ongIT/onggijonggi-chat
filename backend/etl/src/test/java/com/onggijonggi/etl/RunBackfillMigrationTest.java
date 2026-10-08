package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : RunBackfillMigrationTest.java
 * Description : #340 이전에 등록돼 PENDING으로 남은 문서가 업그레이드 migration으로 1회차 처리 작업을 받는지 확인한다.
 *               등록 사건이 없는(원본 저장 중·실패) 문서와 이미 처리된 문서는 회차를 받지 않는다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RunBackfillMigrationTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("backfill").withUsername("test").withPassword("test");

	@Test
	void pendingRegisteredDocumentsGetTheirFirstRunOnUpgrade() throws SQLException {
		flyway("20261002231342000").migrate();
		UUID pending = UUID.randomUUID(), unregistered = UUID.randomUUID(), ready = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			insert(connection, pending, "PENDING", true);
			insert(connection, unregistered, "PENDING", false);
			insert(connection, ready, "READY", true);
		}

		flyway(null).migrate();

		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var query = connection.prepareStatement("select doc_id, run_seq, status from thr_doc_run");
				var rows = query.executeQuery()) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getObject(1, UUID.class)).isEqualTo(pending);
			assertThat(rows.getInt(2)).isEqualTo(1);
			assertThat(rows.getString(3)).isEqualTo("PENDING");
			assertThat(rows.next()).isFalse();
		}
	}

	private static Flyway flyway(String target) {
		var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration");
		if (target != null) configuration.target(target);
		return configuration.load();
	}

	private static void insert(java.sql.Connection connection, UUID id, String status, boolean registered) throws SQLException {
		try (var doc = connection.prepareStatement("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key, status)"
				+ " values (?, ?, ?, ?, 'a.txt', 1, ?, ?)")) {
			doc.setObject(1, id); doc.setObject(2, UUID.randomUUID()); doc.setObject(3, UUID.randomUUID()); doc.setObject(4, UUID.randomUUID());
			doc.setString(5, "a".repeat(64)); doc.setString(6, status);
			doc.executeUpdate();
		}
		if (!registered) return;
		try (var event = connection.prepareStatement("insert into thr_doc_evt(id, tnn_id, thr_id, doc_id, act_user_id, evt_kind, req_key)"
				+ " values (?, ?, ?, ?, ?, 'REGISTERED', ?)")) {
			event.setObject(1, UUID.randomUUID()); event.setObject(2, UUID.randomUUID()); event.setObject(3, UUID.randomUUID());
			event.setObject(4, id); event.setObject(5, UUID.randomUUID()); event.setObject(6, id);
			event.executeUpdate();
		}
	}
}
