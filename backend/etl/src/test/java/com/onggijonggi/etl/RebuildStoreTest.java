package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : RebuildStoreTest.java
 * Description : 다시 만들기 회차(#348)의 대상 선정·운영자 요청 처리와, 그 회차를 워커가 집고 끝낼 때 문서 상태가 바뀌지 않는지를 실제
 *               최신 스키마(PostgreSQL)에서 확인한다. Elasticsearch·워커 없이 돈다. 테스트마다 문서·회차를 비운다(선점은 전체 회차를 본다).
 */
@Testcontainers(disabledWithoutDocker = true)
class RebuildStoreTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("rebuilds").withUsername("test").withPassword("test");
	static JdbcTemplate jdbc;
	static RebuildStore rebuilds;
	static RunStore runs;
	static final Duration LEASE = Duration.ofMinutes(10);

	@BeforeAll
	static void setUp() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		var transactions = new DataSourceTransactionManager(dataSource);
		rebuilds = new RebuildStore(jdbc, transactions);
		runs = new RunStore(jdbc, transactions);
	}

	@BeforeEach
	void clear() {
		jdbc.execute("truncate thr_doc_run, doc_rbl, thr_doc cascade");
	}

	@Test
	void documentsProcessedWithOtherSettingsAreQueuedOnceAndOnlyWhenIdle() throws SQLException {
		UUID current = readyDocument("bge-m3", 1024, "800/1200/100");
		UUID otherModel = readyDocument("old-model", 1024, "800/1200/100");
		UUID otherChunking = readyDocument("bge-m3", 1024, "500/700/50");
		UUID busy = readyDocument("old-model", 1024, "800/1200/100");
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status) values (?, ?, ?, ?, 2, 'PENDING')",
				UUID.randomUUID(), busy, UUID.randomUUID(), UUID.randomUUID());
		UUID failed = readyDocument("old-model", 1024, "800/1200/100");
		jdbc.update("update thr_doc set status = 'FAILED' where id = ?", failed);

		assertThat(rebuilds.queueOutdated("bge-m3", 1024, "800/1200/100", 1)).as("상한만큼만").isEqualTo(1);
		assertThat(rebuilds.queueOutdated("bge-m3", 1024, "800/1200/100", 200)).isEqualTo(1);
		assertThat(rebuilds.queueOutdated("bge-m3", 1024, "800/1200/100", 200)).as("이미 회차가 있으면 다시 만들지 않는다").isZero();

		assertThat(newRuns(current)).isZero();
		assertThat(newRuns(failed)).as("READY가 아니면 대상이 아니다").isZero();
		assertThat(newRuns(busy)).as("진행 중 회차가 있으면 하나 더 만들지 않는다").isEqualTo(1);
		for (UUID doc : new UUID[] {otherModel, otherChunking})
			assertThat(jdbc.queryForMap("select run_seq, status, run_kind, rbl_id from thr_doc_run where doc_id = ? and run_seq = 2", doc))
					.containsEntry("status", "PENDING").containsEntry("run_kind", "REBUILD").containsEntry("rbl_id", null);
	}

	@Test
	void operatorRequestQueuesItsTargetsAndCompletesInOneGo() throws SQLException {
		UUID upToDate = readyDocument("bge-m3", 1024, "800/1200/100");
		UUID outdated = readyDocument("old-model", 1024, "800/1200/100");
		UUID request = UUID.randomUUID();
		jdbc.update("insert into doc_rbl(id, kind, status, req_subj) values (?, 'OUTDATED', 'PENDING', 'admin')", request);

		var handled = rebuilds.handleNextRequest("bge-m3", 1024, "800/1200/100");

		assertThat(handled.request().id()).isEqualTo(request);
		assertThat(handled.targets()).isEqualTo(1);
		assertThat(jdbc.queryForMap("select status, trg_cnt from doc_rbl where id = ?", request))
				.containsEntry("status", "COMPLETED").containsEntry("trg_cnt", 1);
		assertThat(jdbc.queryForObject("select rbl_id from thr_doc_run where doc_id = ? and run_seq = 2", UUID.class, outdated)).isEqualTo(request);
		assertThat(newRuns(upToDate)).isZero();
		assertThat(rebuilds.handleNextRequest("bge-m3", 1024, "800/1200/100")).as("대기 요청이 없다").isNull();

		UUID all = UUID.randomUUID();
		jdbc.update("insert into doc_rbl(id, kind, status, req_subj) values (?, 'ALL', 'PENDING', 'admin')", all);
		assertThat(rebuilds.handleNextRequest("bge-m3", 1024, "800/1200/100").targets())
				.as("진행 중 회차가 있는 문서는 빼고 전체").isEqualTo(1);
		assertThat(newRuns(upToDate)).isEqualTo(1);
	}

	@Test
	void recoveryIsSkippedWhenTheCurrentRunChangedSinceItWasChecked() throws SQLException {
		UUID doc = readyDocument("bge-m3", 1024, "800/1200/100");
		var checked = rebuilds.currentRuns(null, 10).get(0);
		assertThat(checked).isEqualTo(new RebuildStore.CurrentRun(doc, 1, 3));
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, chunk_cnt) values (?, ?, ?, ?, 2, 'DONE', 3)",
				UUID.randomUUID(), doc, UUID.randomUUID(), UUID.randomUUID());

		assertThat(rebuilds.queueRecover(checked)).as("대조한 회차가 이미 대체됐다").isFalse();
		assertThat(rebuilds.queueRecover(new RebuildStore.CurrentRun(doc, 2, 3))).isTrue();
		assertThat(jdbc.queryForObject("select run_kind from thr_doc_run where doc_id = ? and run_seq = 3", String.class, doc)).isEqualTo("RECOVER");
	}

	@Test
	void currentRunsArePagedByDocument() throws SQLException {
		for (int i = 0; i < 3; i++) readyDocument("bge-m3", 1024, "800/1200/100");
		var first = rebuilds.currentRuns(null, 2);
		var rest = rebuilds.currentRuns(first.get(1).document(), 2);
		assertThat(first).hasSize(2);
		assertThat(rest).hasSize(1);
		assertThat(first).extracting(RebuildStore.CurrentRun::document).doesNotContain(rest.get(0).document());
	}

	@Test
	void userDocumentsAreClaimedBeforeRebuildsAndARebuildLeavesTheDocumentReady() throws SQLException {
		UUID ready = readyDocument("old-model", 1024, "800/1200/100");
		rebuilds.queueOutdated("bge-m3", 1024, "800/1200/100", 10);
		jdbc.update("update thr_doc_run set next_at = now() - interval '1 minute' where run_kind = 'REBUILD'");
		UUID uploaded = document("PENDING");
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status) values (?, ?, ?, ?, 1, 'PENDING')",
				UUID.randomUUID(), uploaded, UUID.randomUUID(), UUID.randomUUID());

		RunStore.Job first = runs.claim(LEASE, 5);
		assertThat(first.document()).as("늦게 들어왔어도 사용자 문서가 먼저").isEqualTo(uploaded);
		RunStore.Job rebuild = runs.claim(LEASE, 5);
		assertThat(rebuild.document()).isEqualTo(ready);
		assertThat(rebuild.kind()).isEqualTo(RunStore.REBUILD);
		assertThat(status(ready)).as("처리 중에도 READY — 이전 회차로 검색된다").isEqualTo("READY");
		assertThat(runs.extend(rebuild, LEASE)).isTrue();

		assertThat(runs.complete(rebuild, 4, "bge-m3", 1024, "800/1200/100")).isTrue();
		assertThat(status(ready)).isEqualTo("READY");
		assertThat(jdbc.queryForMap("select status, emb_mdl, chunk_cnt from thr_doc_run where id = ?", rebuild.run()))
				.containsEntry("status", "DONE").containsEntry("emb_mdl", "bge-m3").containsEntry("chunk_cnt", 4);
	}

	@Test
	void aFailedRebuildKeepsTheDocumentReadyButAFailedRecoveryFailsIt() throws SQLException {
		UUID rebuilt = readyDocument("bge-m3", 1024, "800/1200/100");
		UUID recovered = readyDocument("bge-m3", 1024, "800/1200/100");
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, run_kind) values (?, ?, ?, ?, 2, 'PENDING', 'REBUILD')",
				UUID.randomUUID(), rebuilt, UUID.randomUUID(), UUID.randomUUID());
		assertThat(rebuilds.queueRecover(new RebuildStore.CurrentRun(recovered, 1, 3))).isTrue();

		for (int i = 0; i < 2; i++) runs.fail(runs.claim(LEASE, 5), "SOURCE_MISSING");

		assertThat(status(rebuilt)).as("이전 조각이 남아 있어 검색은 된다").isEqualTo("READY");
		assertThat(status(recovered)).as("조각이 없으니 사용자에게 처리 실패와 다시 처리를 보인다").isEqualTo("FAILED");
		assertThat(jdbc.queryForList("select status from thr_doc_run where run_seq = 2", String.class)).containsOnly("FAILED");
	}

	@Test
	void aRebuildOfADocumentThatIsNoLongerReadyIsCancelled() throws SQLException {
		UUID doc = readyDocument("bge-m3", 1024, "800/1200/100");
		UUID run = UUID.randomUUID();
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, run_kind) values (?, ?, ?, ?, 2, 'PENDING', 'REBUILD')",
				run, doc, UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("update thr_doc set status = 'DELETED', pnn = false, deleted_at = now() where id = ?", doc);

		assertThat(runs.claim(LEASE, 5)).isNull();
		assertThat(jdbc.queryForObject("select status from thr_doc_run where id = ?", String.class, run)).isEqualTo("CANCELLED");
	}

	private int newRuns(UUID doc) {
		return jdbc.queryForObject("select count(*) from thr_doc_run where doc_id = ? and run_seq > 1", Integer.class, doc);
	}

	private String status(UUID doc) {
		return jdbc.queryForObject("select status from thr_doc where id = ?", String.class, doc);
	}

	/** READY 문서와 그 1회차(DONE, 청크 3개). */
	private static UUID readyDocument(String model, int dimensions, String chunking) throws SQLException {
		UUID id = document("READY");
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, emb_mdl, emb_dim, chnk_cnf, chunk_cnt)"
				+ " values (?, ?, ?, ?, 1, 'DONE', ?, ?, ?, 3)", UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(), model, dimensions, chunking);
		return id;
	}

	/** 방·사용자 FK는 이 저장소와 무관해 픽스처 연결에서만 건너뛴다. */
	private static UUID document(String status) throws SQLException {
		UUID id = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			try (var doc = connection.prepareStatement("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key, status)"
					+ " values (?, ?, ?, ?, 'a.txt', 1, ?, ?)")) {
				doc.setObject(1, id); doc.setObject(2, UUID.randomUUID()); doc.setObject(3, UUID.randomUUID()); doc.setObject(4, UUID.randomUUID());
				doc.setString(5, "a".repeat(64)); doc.setString(6, status);
				doc.executeUpdate();
			}
		}
		return id;
	}
}
