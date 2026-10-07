package com.onggijonggi.etl;

import com.onggijonggi.common.document.ThreadDocumentStates;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Class Name : RunStore.java
 * Description : 처리 회차(thr_doc_run)의 선점·연장·완료·실패·재시도·정리 표시. 회차 종류(INGEST·REBUILD·RECOVER, #348)마다 처리 중
 *               문서가 있어야 할 상태와 실패 처리가 다르다(Job.workingStatus, failLocked). 모든 메서드는 짧은 트랜잭션이고, 원본 읽기·임베딩·
 *               적재 같은 외부 호출 동안에는 DB 잠금을 쥐지 않는다. 잠금 순서는 문서(thr_doc) → 회차(thr_doc_run)로 A와 같다.
 *               선점 시한·재시도 시각은 DB 시계(now())로 계산한다 — 비교도 now()로 하므로 앱과 DB의 시계 차이가 끼지 않는다.
 */
@Component
public class RunStore {

	// 회차 종류(thr_doc_run.run_kind). SQL·마이그레이션에도 같은 글자가 리터럴로 있다(RebuildStore, 선점 인덱스).
	/** 문서 상태를 PENDING → PROCESSING → READY|FAILED로 바꾸는 처리(최초 처리·사용자 재처리). */
	public static final String INGEST = "INGEST";
	/** READY 문서의 조각을 상태를 바꾸지 않고 다시 만든다(설정 변경·운영자 요청). 실패해도 이전 조각이 남아 문서는 READY 그대로다. */
	public static final String REBUILD = "REBUILD";
	/** 조각이 없어진 READY 문서를 다시 만든다(자동 복구). 실패하면 검색할 조각이 없으므로 문서를 FAILED로 바꾼다. */
	public static final String RECOVER = "RECOVER";
	/** 일시 오류로 재시도를 다 쓴 실패의 사유 코드 접두어(IngestionPipeline). 그 뒤에 마지막 일시 오류 코드가 붙는다. */
	public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

	/**
	 * 선점한 회차와 처리에 필요한 문서 snapshot. digest·attempt는 원본 위치, fileName은 추출 형식을 정한다.
	 * kind는 회차 종류(INGEST·REBUILD·RECOVER) — 처리 중 문서가 어느 상태여야 하는지와 실패 처리를 정한다.
	 */
	public record Job(UUID run, UUID document, UUID tenant, UUID thread, int runSeq, int attempts,
			String fileName, String digest, UUID sourceAttempt, String kind) {

		public Job(UUID run, UUID document, UUID tenant, UUID thread, int runSeq, int attempts,
				String fileName, String digest, UUID sourceAttempt) {
			this(run, document, tenant, thread, runSeq, attempts, fileName, digest, sourceAttempt, INGEST);
		}

		/** 처리하는 동안 문서가 있어야 하는 상태. INGEST는 처리 중(PROCESSING), 다시 만들기는 READY 그대로다. */
		String workingStatus() {
			return INGEST.equals(kind) ? "PROCESSING" : "READY";
		}
	}

	/** 정리 대상 회차. */
	public record Stale(UUID run, UUID document, int runSeq) { }

	/** 한 번의 claim에서 취소·건너뛰기로 넘길 수 있는 회차 수. 넘으면 다음 폴링에서 다시 본다. */
	private static final int MAX_SKIPS = 20;
	/** 잠그기 전에 고르는 후보 수. 다른 워커가 잡고 있는 회차를 건너뛸 여유다. */
	private static final int CANDIDATES = 5;
	/** 회차 보충을 기다리는 시간. 막 등록 확정 중인 문서(회차는 같은 트랜잭션에서 생김)와 겹치지 않게 둔다. */
	static final String ORPHAN_GRACE = "1 minute";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public RunStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	/**
	 * 기한이 된 회차 하나를 선점한다. PENDING(새 작업·재시도 대기)과 선점 시한이 지난 RUNNING(죽은 워커)을 같이 본다.
	 * 문서가 사라졌거나 삭제됐으면 회차를 CANCELLED로 두고 다음을 본다. 없으면 null.
	 */
	public Job claim(Duration lease, int maxAttempts) {
		for (int skipped = 0; skipped < MAX_SKIPS; skipped++) {
			Claim claim = transactions.execute(tx -> claimOne(lease, maxAttempts));
			if (claim == null) return null;
			if (claim.job() != null) return claim.job();
		}
		return null;
	}

	private record Claim(Job job) { }

	private Claim claimOne(Duration lease, int maxAttempts) {
		// 후보는 잠그지 않고 고른 뒤 문서 → 회차 순서로 잠근다(A의 등록·재처리와 같은 순서라 교착이 없다).
		// 다른 워커가 같은 회차를 잡고 있으면 회차 잠금에서 건너뛰고 다음 후보를 본다.
		// 사용자가 올리거나 다시 처리한 문서(INGEST)를 먼저 집는다 — 일괄 다시 만들기가 수백 건 쌓여도 새 문서가 뒤로 밀리지 않는다.
		// 정렬 식은 선점 인덱스(ix_thr_doc_run_claim)의 식과 글자 그대로 같아야 인덱스를 탄다 — 바꾸면 마이그레이션도 함께 바꾼다.
		var candidates = jdbc.queryForList("select id, doc_id from thr_doc_run where status in ('PENDING', 'RUNNING')"
				+ " and next_at <= now() order by (run_kind <> 'INGEST'), next_at, id limit " + CANDIDATES);
		if (candidates.isEmpty()) return null;
		for (var candidate : candidates) {
			UUID run = (UUID) candidate.get("id");
			UUID document = (UUID) candidate.get("doc_id");
			var docs = jdbc.queryForList("select status, file_name, src_key, src_att_id from thr_doc where id = ? for update", document);
			var locked = jdbc.queryForList("select status, att_cnt, run_kind from thr_doc_run where id = ? and status in ('PENDING', 'RUNNING')"
					+ " and next_at <= now() for update skip locked", run);
			if (locked.isEmpty()) continue;
			return lockedClaim(run, document, docs, locked.get(0), lease, maxAttempts);
		}
		// 기한이 된 후보를 모두 다른 워커가 잡고 있다. 다음 폴링에서 다시 본다.
		return null;
	}

	private Claim lockedClaim(UUID run, UUID document, List<Map<String, Object>> docs, Map<String, Object> current,
			Duration lease, int maxAttempts) {
		String status = docs.isEmpty() ? null : (String) docs.get(0).get("status");
		if (status == null || status.equals("DELETED")) {
			jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ?", run);
			return new Claim(null);
		}
		// 시한이 지난 RUNNING은 처리 중에 워커가 죽은 회차다. 실패 처리(handle)를 거치지 못했으므로 시도 상한을 여기서 본다 —
		// 프로세스를 죽이는 문서(메모리 초과 등)가 시한마다 다시 집혀 워커를 계속 죽이지 않게 한다.
		String kind = (String) current.get("run_kind");
		if ("RUNNING".equals(current.get("status")) && (Integer) current.get("att_cnt") >= maxAttempts) {
			failLocked(run, document, kind, RETRY_EXHAUSTED);
			return new Claim(null);
		}
		if (!claimable(document, kind, status)) {
			jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ?", run);
			return new Claim(null);
		}
		jdbc.update("update thr_doc_run set status = 'RUNNING', att_cnt = att_cnt + 1, next_at = now() + ? * interval '1 millisecond',"
				+ " updated_at = now() where id = ?", lease.toMillis(), run);
		var row = jdbc.queryForMap("select tnn_id, thr_id, run_seq, att_cnt from thr_doc_run where id = ?", run);
		var doc = docs.get(0);
		return new Claim(new Job(run, document, (UUID) row.get("tnn_id"), (UUID) row.get("thr_id"), (Integer) row.get("run_seq"),
				(Integer) row.get("att_cnt"), (String) doc.get("file_name"), (String) doc.get("src_key"), (UUID) doc.get("src_att_id"), kind));
	}

	/**
	 * 문서 잠금 안에서 이 회차를 지금 처리해도 되는지 본다. 다시 만들기(REBUILD·RECOVER)는 READY 문서에만 하고 상태를 바꾸지 않는다 —
	 * 그사이 실패·재처리로 상태가 바뀌었으면 그쪽 처리에 맡긴다. INGEST는 처음 집으면 문서를 처리 중으로 바꾸고(죽은 워커의 회차를
	 * 다시 집으면 이미 PROCESSING이다), READY·FAILED 문서에 남은 회차(운영 중 수동 조작 등)는 처리하지 않는다.
	 */
	private boolean claimable(UUID document, String kind, String status) {
		if (!INGEST.equals(kind)) return status.equals("READY");
		if (status.equals("PENDING")) return ThreadDocumentStates.transition(jdbc, document, "PENDING", "PROCESSING");
		return status.equals("PROCESSING");
	}

	/** 단계 사이에 선점 시한을 늘린다. 문서가 아직 처리할 상태면 true — false면 그사이 삭제·취소된 것이다. */
	public boolean extend(Job job, Duration lease) {
		Boolean alive = transactions.execute(tx -> {
			var docs = jdbc.queryForList("select status from thr_doc where id = ?", job.document());
			if (docs.isEmpty() || !job.workingStatus().equals(docs.get(0).get("status"))) return false;
			return jdbc.update("update thr_doc_run set next_at = now() + ? * interval '1 millisecond', updated_at = now()"
					+ " where id = ? and status = 'RUNNING' and att_cnt = ?", lease.toMillis(), job.run(), job.attempts()) == 1;
		});
		return Boolean.TRUE.equals(alive);
	}

	/**
	 * 모든 청크를 적재·대조한 뒤 READY로 확정한다. 그사이 삭제됐으면 false이고 회차는 CANCELLED가 된다 — 적재한 청크는 RunSweeper가 지운다.
	 * 호출자가 바로 지우지 않는다: false가 이 워커의 소유가 끝난 경우일 수도 있어, 지금 소유자의 청크를 지우면 안 된다.
	 */
	public boolean complete(Job job, int chunks, String model, int dimensions, String chunking) {
		Boolean done = transactions.execute(tx -> {
			var docs = jdbc.queryForList("select status from thr_doc where id = ? for update", job.document());
			if (!owned(job)) return false;
			// 다시 만들기는 문서 상태를 바꾸지 않는다 — 회차가 DONE이 되는 순간 검색(가장 큰 DONE 회차)이 새 조각으로 넘어간다.
			boolean kept = INGEST.equals(job.kind())
					? ThreadDocumentStates.transition(jdbc, job.document(), "PROCESSING", "READY")
					: !docs.isEmpty() && "READY".equals(docs.get(0).get("status"));
			if (!kept) {
				jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ?", job.run());
				return false;
			}
			jdbc.update("update thr_doc_run set status = 'DONE', err = null, emb_mdl = ?, emb_dim = ?, chnk_cnf = ?, chunk_cnt = ?,"
					+ " updated_at = now() where id = ?", model, dimensions, chunking, chunks, job.run());
			return true;
		});
		return Boolean.TRUE.equals(done);
	}

	/** 최종 실패. 회차에 사유 코드를 남기고 회차 종류에 따라 문서를 FAILED로 둔다(failLocked). 적재했던 청크는 정리 작업이 지운다. */
	public void fail(Job job, String code) {
		transactions.executeWithoutResult(tx -> {
			jdbc.queryForList("select id from thr_doc where id = ? for update", job.document());
			if (!owned(job)) return;
			failLocked(job.run(), job.document(), job.kind(), code);
		});
	}

	/**
	 * 문서 잠금 안에서 회차를 실패로 끝낸다. INGEST는 문서를 FAILED로 바꾼다 — 사용자에게 "처리 실패"와 다시 처리 버튼을 보인다.
	 * REBUILD는 이전 회차 조각이 남아 검색이 되므로 문서를 READY로 둔다. RECOVER는 원본 손상·없음처럼 다시 해도 안 되는 실패만
	 * 문서를 FAILED로 바꾼다 — 임베딩 서버·Elasticsearch가 오래 끊겨 재시도를 다 쓴 경우는 READY로 두어, 장애가 풀린 뒤 다음 대조가
	 * 다시 잡게 한다(장애가 문서 상태를 망가뜨려 사용자가 문서마다 다시 처리를 누르게 하지 않는다). 그사이 삭제·상태가 바뀐 문서면
	 * 실패가 아니라 취소다(complete와 같은 기록). 청크는 어느 쪽이든 정리 작업이 지운다.
	 */
	private void failLocked(UUID run, UUID document, String kind, String code) {
		boolean exhausted = code != null && code.startsWith(RETRY_EXHAUSTED);
		boolean failed = switch (kind) {
			case REBUILD -> stillReady(document);
			case RECOVER -> exhausted ? stillReady(document) : ThreadDocumentStates.transition(jdbc, document, "READY", "FAILED");
			default -> ThreadDocumentStates.transition(jdbc, document, "PROCESSING", "FAILED");
		};
		jdbc.update("update thr_doc_run set status = ?, err = ?, updated_at = now() where id = ?", failed ? "FAILED" : "CANCELLED", code, run);
	}

	private boolean stillReady(UUID document) {
		return !jdbc.queryForList("select id from thr_doc where id = ? and status = 'READY'", document).isEmpty();
	}

	/** 일시 오류. 문서 상태는 그대로(INGEST는 PROCESSING, 다시 만들기는 READY) 두고 회차만 다음 시도 시각으로 미룬다. */
	public void retryLater(Job job, String code, Duration delay) {
		jdbc.update("update thr_doc_run set status = 'PENDING', err = ?, next_at = now() + ? * interval '1 millisecond', updated_at = now()"
				+ " where id = ? and status = 'RUNNING' and att_cnt = ?", code, delay.toMillis(), job.run(), job.attempts());
	}

	/**
	 * 종료 중이라 끝내지 못한 회차를 바로 다시 집을 수 있게 놓아준다. 이번 시도는 세지 않는다. 아직 이 워커 소유일 때만.
	 * 한계: 시도 횟수를 되돌리므로 다른 인스턴스가 바로 다시 집으면 같은 att_cnt가 되어, 종료 직전까지 남은 이 워커 스레드의 소유 확인이
	 * 통과할 수 있다. ETL은 한 인스턴스로 띄우고 남은 스레드는 데몬이라 JVM과 함께 끝난다. 여러 인스턴스로 늘릴 때 소유 토큰을 따로 둔다.
	 */
	public void release(Job job) {
		jdbc.update("update thr_doc_run set status = 'PENDING', att_cnt = att_cnt - 1, next_at = now(), updated_at = now()"
				+ " where id = ? and status = 'RUNNING' and att_cnt = ?", job.run(), job.attempts());
	}

	/** 처리 도중 문서가 삭제됐다. 적재했던 청크는 정리 작업이 지운다. */
	public void cancel(Job job) {
		jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ? and status = 'RUNNING' and att_cnt = ?",
				job.run(), job.attempts());
	}

	/**
	 * 이 워커가 아직 이 회차를 쥐고 있는가. 선점 시한이 지나 다른 워커가 다시 집으면 시도 횟수가 올라가므로, 늦게 끝난 이전 워커가
	 * 회차를 확정·취소하지 못한다. 두 워커의 청크는 같은 ID라 덮어써져 중복되지 않는다.
	 */
	private boolean owned(Job job) {
		return !jdbc.queryForList("select id from thr_doc_run where id = ? and status = 'RUNNING' and att_cnt = ? for update",
				job.run(), job.attempts()).isEmpty();
	}

	/**
	 * 청크를 지울 회차. 먼저 더는 쓰지 않는 문서의 회차 — 실패·취소된 회차와, 삭제·방 삭제·처리 실패 문서의 완료 회차 — 를 오래된 순으로,
	 * 남은 자리에 재처리·다시 만들기로 대체된 완료 회차를 본다. 일괄 다시 만들기로 대체 회차가 수만 건 쌓여도 사용자가 지운 문서의
	 * 본문이 그 뒤에 밀려 Elasticsearch에 오래 남지 않게 한다. 처리 실패 문서의 완료 회차는 자동 복구가 영구 실패한 경우다(검색되지
	 * 않는데 남은 조각이 있다). 회차 행은 FK가 없어 문서가 cascade로 사라져도 남는다.
	 * 대체된 회차는 새 회차가 끝나고 grace가 지난 뒤에 지운다 — 다시 만들기는 READY 문서의 회차를 검색 중에 바꾸므로, 바뀌기 직전에
	 * 범위(문서, 이전 회차)를 읽은 검색이 Elasticsearch에 묻기 전에 청크가 지워지면 그 검색이 빈 결과가 된다.
	 * 앞의 status 조건은 정리 인덱스(ix_thr_doc_run_sweep)를 타게 하려고 둔다 — 뒤의 조건만으로는 PURGED 행까지 훑는다.
	 */
	public List<Stale> stale(int limit, Duration grace) {
		List<Stale> due = new ArrayList<>(jdbc.query("select r.id, r.doc_id, r.run_seq from thr_doc_run r where r.status in ('DONE', 'FAILED', 'CANCELLED')"
				+ " and (r.status in ('FAILED', 'CANCELLED')"
				+ " or not exists (select 1 from thr_doc d where d.id = r.doc_id and d.status not in ('DELETED', 'FAILED')))"
				+ " order by r.updated_at limit ?", STALE, limit));
		if (due.size() < limit)
			due.addAll(jdbc.query("select r.id, r.doc_id, r.run_seq from thr_doc_run r where r.status = 'DONE'"
					+ " and exists (select 1 from thr_doc d where d.id = r.doc_id and d.status not in ('DELETED', 'FAILED'))"
					+ " and exists (select 1 from thr_doc_run n where n.doc_id = r.doc_id and n.status = 'DONE' and n.run_seq > r.run_seq"
					+ " and n.updated_at <= now() - ? * interval '1 millisecond')"
					+ " order by r.updated_at limit ?", STALE, grace.toMillis(), limit - due.size()));
		return due;
	}

	private static final RowMapper<Stale> STALE =
			(rs, row) -> new Stale(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3));

	/**
	 * 등록됐는데(REGISTERED 사건) 진행 중 회차가 없는 PENDING 문서에 회차를 보충한다. 회차는 등록 확정과 같은 트랜잭션에서 만들어지지만,
	 * 이 migration 전 버전의 BFF가 롤링 배포 중에 확정한 등록이나 수동 조작으로 회차 없는 문서가 생기면 ETL이 영영 집지 않는다.
	 * 막 등록된 문서와 겹치지 않게 1분 넘게 그대로인 문서만 본다. 보충한 수를 돌려준다.
	 */
	public int requeueOrphans(int limit) {
		List<UUID> orphans = jdbc.queryForList("select d.id from thr_doc d where d.status = 'PENDING'"
				+ " and d.updated_at < now() - interval '" + ORPHAN_GRACE + "'"
				+ " and exists (select 1 from thr_doc_evt e where e.doc_id = d.id and e.evt_kind = 'REGISTERED')"
				+ " and not exists (select 1 from thr_doc_run r where r.doc_id = d.id and r.status in ('PENDING', 'RUNNING'))"
				+ " order by d.updated_at limit ?", UUID.class, limit);
		int queued = 0;
		for (UUID document : orphans) {
			Boolean created = transactions.execute(tx -> {
				var docs = jdbc.queryForList("select tnn_id, thr_id from thr_doc where id = ? and status = 'PENDING' for update", document);
				if (docs.isEmpty() || !jdbc.queryForList("select id from thr_doc_run where doc_id = ? and status in ('PENDING', 'RUNNING')",
						document).isEmpty()) return false;
				ThreadDocumentStates.createRun(jdbc, document, (UUID) docs.get(0).get("tnn_id"), (UUID) docs.get(0).get("thr_id"));
				return true;
			});
			if (Boolean.TRUE.equals(created)) queued++;
		}
		return queued;
	}

	/** 정리에 실패한 회차를 정리 순서의 뒤로 미룬다 — 계속 실패하는 회차가 한 번에 보는 수(limit)를 채워 뒤 회차를 막지 않게 한다. */
	public void postpone(Stale stale) {
		jdbc.update("update thr_doc_run set updated_at = now() where id = ? and status in ('DONE', 'FAILED', 'CANCELLED')", stale.run());
	}

	public void purged(Stale stale) {
		jdbc.update("update thr_doc_run set status = 'PURGED', updated_at = now() where id = ? and status in ('DONE', 'FAILED', 'CANCELLED')",
				stale.run());
	}
}
