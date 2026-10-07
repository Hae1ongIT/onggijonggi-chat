package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.rag.ChunkRebuildInProgressException;
import com.onggijonggi.api.rag.ChunkRebuildService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : ChunkRebuildPostgresTest.java
 * Description : 일괄 재처리 요청(#348)을 실제 최신 Flyway·PostgreSQL에서 확인한다 — 대기 중이거나 만든 회차가 아직 처리 중인 요청이
 *               있으면 새 요청을 받지 않고, 진행 상황(남은·실패 수)을 회차에서 집계한다. 회차 생성은 ETL(RebuildStoreTest)이 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ChunkRebuildPostgresTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("rebuilds").withUsername("test").withPassword("test");
	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
	}

	@Autowired ChunkRebuildService service;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		jdbc.execute("delete from thr_doc_run where rbl_id is not null");
		jdbc.execute("delete from doc_rbl");
	}

	@Test
	void aSecondRequestIsRefusedUntilThePreviousOneHasFinished() {
		var first = service.request(ChunkRebuildService.Scope.ALL, "admin");
		assertThat(first.status()).isEqualTo("PENDING");
		assertThat(first.targets()).as("ETL이 집기 전").isNull();
		assertRefused(first.id());

		// ETL이 회차 둘을 만들었다 — 하나는 처리 중, 하나는 실패해 정리됨.
		jdbc.update("update doc_rbl set status = 'COMPLETED', trg_cnt = 2, completed_at = now() where id = ?", first.id());
		UUID running = run(first.id(), "RUNNING", null);
		run(first.id(), "PURGED", "SOURCE_MISSING");
		assertRefused(first.id());
		assertThat(service.find(first.id()).orElseThrow())
				.extracting(ChunkRebuildService.Rebuild::targets, ChunkRebuildService.Rebuild::remaining, ChunkRebuildService.Rebuild::failed)
				.containsExactly(2, 1, 1);

		jdbc.update("update thr_doc_run set status = 'DONE' where id = ?", running);
		var next = service.request(ChunkRebuildService.Scope.OUTDATED, "admin");
		assertThat(next.scope()).isEqualTo(ChunkRebuildService.Scope.OUTDATED);
		assertThat(service.recent()).extracting(ChunkRebuildService.Rebuild::id).containsExactly(next.id(), first.id());
	}

	/** 두 요청이 동시에 들어와도 하나만 받고 다른 하나는 409다 — 500이 되거나 대기 요청이 둘이 되지 않는다. */
	@Test
	void concurrentRequestsQueueExactlyOne() throws Exception {
		var start = new java.util.concurrent.CyclicBarrier(2);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			java.util.concurrent.Callable<Object> call = () -> {
				start.await();
				try {
					return service.request(ChunkRebuildService.Scope.ALL, "admin");
				} catch (ChunkRebuildInProgressException refused) {
					return refused;
				}
			};
			var results = pool.invokeAll(java.util.List.of(call, call));
			assertThat(results.stream().map(result -> {
				try {
					return result.get().getClass().getSimpleName();
				} catch (Exception error) {
					return error.toString();
				}
			}).toList()).containsExactlyInAnyOrder("Rebuild", "ChunkRebuildInProgressException");
		} finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from doc_rbl where status = 'PENDING'", Integer.class)).isEqualTo(1);
	}

	/** 실패 수는 사유 코드가 남은 회차만 센다 — 성공해 정리된 회차(PURGED, 사유 없음)는 세지 않는다. */
	@Test
	void onlyRunsThatEndedWithAnErrorCountAsFailed() {
		var request = service.request(ChunkRebuildService.Scope.ALL, "admin");
		jdbc.update("update doc_rbl set status = 'COMPLETED', trg_cnt = 3, completed_at = now() where id = ?", request.id());
		run(request.id(), "PURGED", null);
		run(request.id(), "DONE", null);
		run(request.id(), "FAILED", "SOURCE_MISSING");

		assertThat(service.find(request.id()).orElseThrow())
				.extracting(ChunkRebuildService.Rebuild::remaining, ChunkRebuildService.Rebuild::failed).containsExactly(0, 1);
		assertThat(service.find(UUID.randomUUID())).isEmpty();
	}

	private void assertRefused(UUID running) {
		assertThatThrownBy(() -> service.request(ChunkRebuildService.Scope.ALL, "admin"))
				.isInstanceOfSatisfying(ChunkRebuildInProgressException.class, refused -> assertThat(refused.running()).isEqualTo(running));
	}

	/** 요청이 만든 회차. 회차 행은 FK가 없어 문서 없이 넣을 수 있다. */
	private UUID run(UUID request, String status, String error) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, err, run_kind, rbl_id)"
				+ " values (?, ?, ?, ?, 2, ?, ?, 'REBUILD', ?)", id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, error, request);
		return id;
	}
}
