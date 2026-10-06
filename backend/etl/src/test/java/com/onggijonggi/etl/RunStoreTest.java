package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.document.ThreadDocumentStates;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : RunStoreTest.java
 * Description : 처리 회차 저장소의 실패·보충 분기를 실제 최신 스키마(PostgreSQL)에서 확인한다. Elasticsearch·워커 없이 돈다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RunStoreTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("runs").withUsername("test").withPassword("test");
	static JdbcTemplate jdbc;
	static RunStore runs;

	@BeforeAll
	static void setUp() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		runs = new RunStore(jdbc, new DataSourceTransactionManager(dataSource));
	}

	@Test
	void failureOfADocumentDeletedMeanwhileIsRecordedAsCancelled() throws SQLException {
		UUID doc = insertDocument("PROCESSING", true, false);
		UUID run = UUID.randomUUID();
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, att_cnt) values (?, ?, ?, ?, 1, 'RUNNING', 1)",
				run, doc, UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("update thr_doc set status='DELETED', pnn=false, deleted_at=now() where id=?", doc);

		runs.fail(new RunStore.Job(run, doc, null, null, 1, 1, "a.txt", null, null), "EMPTY_TEXT");

		assertThat(jdbc.queryForMap("select status, err from thr_doc_run where id=?", run))
				.containsEntry("status", "CANCELLED").containsEntry("err", "EMPTY_TEXT");
		assertThat(jdbc.queryForObject("select status from thr_doc where id=?", String.class, doc)).isEqualTo("DELETED");
	}

	@Test
	void onlyRegisteredStalePendingDocumentsWithoutAnActiveRunAreRequeued() throws SQLException {
		UUID orphan = insertDocument("PENDING", true, true);
		UUID fresh = insertDocument("PENDING", true, false);
		UUID unregistered = insertDocument("PENDING", false, true);
		UUID queued = insertDocument("PENDING", true, true);
		ThreadDocumentStates.createRun(jdbc, queued, UUID.randomUUID(), UUID.randomUUID());

		assertThat(runs.requeueOrphans(20)).isEqualTo(1);

		assertThat(runCount(orphan)).isEqualTo(1);
		assertThat(runCount(fresh)).as("막 등록된 문서(" + RunStore.ORPHAN_GRACE + " 이내)는 건드리지 않는다").isZero();
		assertThat(runCount(unregistered)).as("등록 사건이 없는 문서는 아직 원본 저장 중이다").isZero();
		assertThat(runCount(queued)).as("진행 중 회차가 있으면 하나 더 만들지 않는다").isEqualTo(1);
		assertThat(runs.requeueOrphans(20)).isZero();
	}

	@Test
	void claimFailsARunThatExhaustedItsAttemptsWithoutProcessingIt() throws SQLException {
		UUID doc = insertDocument("PROCESSING", true, false);
		UUID run = UUID.randomUUID();
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, att_cnt, next_at) values (?, ?, ?, ?, 1, 'RUNNING', 2, now() - interval '1 second')",
				run, doc, UUID.randomUUID(), UUID.randomUUID());

		assertThat(runs.claim(Duration.ofMinutes(10), 2)).isNull();

		assertThat(jdbc.queryForObject("select status from thr_doc where id=?", String.class, doc)).isEqualTo("FAILED");
		assertThat(jdbc.queryForMap("select status, err from thr_doc_run where id=?", run))
				.containsEntry("status", "FAILED").containsEntry("err", "RETRY_EXHAUSTED");
	}

	private int runCount(UUID doc) {
		return jdbc.queryForObject("select count(*) from thr_doc_run where doc_id=?", Integer.class, doc);
	}

	/** 방·사용자 FK는 이 저장소와 무관해 픽스처 연결에서만 건너뛴다. stale이면 마지막 변경을 2분 전으로 둔다. */
	private static UUID insertDocument(String status, boolean registered, boolean stale) throws SQLException {
		UUID id = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			try (var doc = connection.prepareStatement("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key, status,"
					+ " updated_at) values (?, ?, ?, ?, 'a.txt', 1, ?, ?, now() - ?::interval)")) {
				doc.setObject(1, id); doc.setObject(2, UUID.randomUUID()); doc.setObject(3, UUID.randomUUID()); doc.setObject(4, UUID.randomUUID());
				doc.setString(5, "a".repeat(64)); doc.setString(6, status); doc.setString(7, stale ? "2 minutes" : "0 seconds");
				doc.executeUpdate();
			}
			if (registered) {
				try (var event = connection.prepareStatement("insert into thr_doc_evt(id, tnn_id, thr_id, doc_id, act_user_id, evt_kind, req_key)"
						+ " values (?, ?, ?, ?, ?, 'REGISTERED', ?)")) {
					event.setObject(1, UUID.randomUUID()); event.setObject(2, UUID.randomUUID()); event.setObject(3, UUID.randomUUID());
					event.setObject(4, id); event.setObject(5, UUID.randomUUID()); event.setObject(6, id);
					event.executeUpdate();
				}
			}
		}
		return id;
	}
}
