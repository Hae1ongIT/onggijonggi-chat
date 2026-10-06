package com.onggijonggi.etl;

import com.onggijonggi.common.document.ThreadDocumentStates;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Class Name : RunStore.java
 * Description : 처리 회차(thr_doc_run)의 선점·연장·완료·실패·재시도·정리 표시. 모든 메서드는 짧은 트랜잭션이고, 원본 읽기·임베딩·
 *               적재 같은 외부 호출 동안에는 DB 잠금을 쥐지 않는다. 잠금 순서는 문서(thr_doc) → 회차(thr_doc_run)로 A와 같다.
 *               선점 시한·재시도 시각은 DB 시계(now())로 계산한다 — 비교도 now()로 하므로 앱과 DB의 시계 차이가 끼지 않는다.
 */
@Component
public class RunStore {

	/** 선점한 회차와 처리에 필요한 문서 snapshot. digest·attempt는 원본 위치, fileName은 추출 형식을 정한다. */
	public record Job(UUID run, UUID document, UUID tenant, UUID thread, int runSeq, int attempts,
			String fileName, String digest, UUID sourceAttempt) { }

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
		var candidates = jdbc.queryForList("select id, doc_id from thr_doc_run where status in ('PENDING', 'RUNNING')"
				+ " and next_at <= now() order by next_at, id limit " + CANDIDATES);
		if (candidates.isEmpty()) return null;
		for (var candidate : candidates) {
			UUID run = (UUID) candidate.get("id");
			UUID document = (UUID) candidate.get("doc_id");
			var docs = jdbc.queryForList("select status, file_name, src_key, src_att_id from thr_doc where id = ? for update", document);
			var locked = jdbc.queryForList("select status, att_cnt from thr_doc_run where id = ? and status in ('PENDING', 'RUNNING')"
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
		if ("RUNNING".equals(current.get("status")) && (Integer) current.get("att_cnt") >= maxAttempts) {
			ThreadDocumentStates.transition(jdbc, document, "PROCESSING", "FAILED");
			jdbc.update("update thr_doc_run set status = 'FAILED', err = 'RETRY_EXHAUSTED', updated_at = now() where id = ?", run);
			return new Claim(null);
		}
		// 처음 집는 회차면 문서를 처리 중으로 바꾼다. 죽은 워커의 회차를 다시 집는 경우 문서는 이미 PROCESSING이다.
		if (status.equals("PENDING")) ThreadDocumentStates.transition(jdbc, document, "PENDING", "PROCESSING");
		else if (!status.equals("PROCESSING")) {
			// READY·FAILED 문서에 남은 회차(운영 중 수동 조작 등)는 처리하지 않는다.
			jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ?", run);
			return new Claim(null);
		}
		jdbc.update("update thr_doc_run set status = 'RUNNING', att_cnt = att_cnt + 1, next_at = now() + ? * interval '1 millisecond',"
				+ " updated_at = now() where id = ?", lease.toMillis(), run);
		var row = jdbc.queryForMap("select tnn_id, thr_id, run_seq, att_cnt from thr_doc_run where id = ?", run);
		var doc = docs.get(0);
		return new Claim(new Job(run, document, (UUID) row.get("tnn_id"), (UUID) row.get("thr_id"), (Integer) row.get("run_seq"),
				(Integer) row.get("att_cnt"), (String) doc.get("file_name"), (String) doc.get("src_key"), (UUID) doc.get("src_att_id")));
	}

	/** 단계 사이에 선점 시한을 늘린다. 문서가 아직 처리 중이면 true — false면 그사이 삭제·취소된 것이다. */
	public boolean extend(Job job, Duration lease) {
		Boolean alive = transactions.execute(tx -> {
			var docs = jdbc.queryForList("select status from thr_doc where id = ?", job.document());
			if (docs.isEmpty() || !"PROCESSING".equals(docs.get(0).get("status"))) return false;
			return jdbc.update("update thr_doc_run set next_at = now() + ? * interval '1 millisecond', updated_at = now()"
					+ " where id = ? and status = 'RUNNING' and att_cnt = ?", lease.toMillis(), job.run(), job.attempts()) == 1;
		});
		return Boolean.TRUE.equals(alive);
	}

	/** 모든 청크를 적재·대조한 뒤 READY로 확정한다. 그사이 삭제됐으면 false — 호출자가 방금 적재한 청크를 지운다. */
	public boolean complete(Job job, int chunks, String model, int dimensions, String chunking) {
		Boolean done = transactions.execute(tx -> {
			jdbc.queryForList("select id from thr_doc where id = ? for update", job.document());
			if (!owned(job)) return false;
			if (!ThreadDocumentStates.transition(jdbc, job.document(), "PROCESSING", "READY")) {
				jdbc.update("update thr_doc_run set status = 'CANCELLED', updated_at = now() where id = ?", job.run());
				return false;
			}
			jdbc.update("update thr_doc_run set status = 'DONE', err = null, emb_mdl = ?, emb_dim = ?, chnk_cnf = ?, chunk_cnt = ?,"
					+ " updated_at = now() where id = ?", model, dimensions, chunking, chunks, job.run());
			return true;
		});
		return Boolean.TRUE.equals(done);
	}

	/** 최종 실패. 문서를 FAILED로 두고 회차에 사유 코드를 남긴다. 적재했던 청크는 정리 작업이 지운다. */
	public void fail(Job job, String code) {
		transactions.executeWithoutResult(tx -> {
			jdbc.queryForList("select id from thr_doc where id = ? for update", job.document());
			if (!owned(job)) return;
			// 그사이 삭제된 문서면 실패가 아니라 취소다(complete와 같은 기록). 청크는 어느 쪽이든 정리 작업이 지운다.
			String status = ThreadDocumentStates.transition(jdbc, job.document(), "PROCESSING", "FAILED") ? "FAILED" : "CANCELLED";
			jdbc.update("update thr_doc_run set status = ?, err = ?, updated_at = now() where id = ?", status, code, job.run());
		});
	}

	/** 일시 오류. 문서는 PROCESSING(처리 중)으로 둔 채 회차만 다음 시도 시각으로 미룬다. */
	public void retryLater(Job job, String code, Duration delay) {
		jdbc.update("update thr_doc_run set status = 'PENDING', err = ?, next_at = now() + ? * interval '1 millisecond', updated_at = now()"
				+ " where id = ? and status = 'RUNNING' and att_cnt = ?", code, delay.toMillis(), job.run(), job.attempts());
	}

	/** 종료 중이라 끝내지 못한 회차를 바로 다시 집을 수 있게 놓아준다. 이번 시도는 세지 않는다. 아직 이 워커 소유일 때만. */
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
	 * 청크를 지울 회차: 실패·취소된 회차, 그리고 살아 있는 문서의 최신 완료 회차가 아닌 완료 회차(삭제·방 삭제된 문서, 재처리로
	 * 대체된 회차). 회차 행은 FK가 없어 문서가 cascade로 사라져도 남는다.
	 */
	public List<Stale> stale(int limit) {
		return jdbc.query("select r.id, r.doc_id, r.run_seq from thr_doc_run r where r.status in ('FAILED', 'CANCELLED')"
				+ " or (r.status = 'DONE' and (not exists (select 1 from thr_doc d where d.id = r.doc_id and d.status <> 'DELETED')"
				+ " or exists (select 1 from thr_doc_run n where n.doc_id = r.doc_id and n.status = 'DONE' and n.run_seq > r.run_seq)))"
				+ " order by r.updated_at limit ?",
				(rs, row) -> new Stale(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)), limit);
	}

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

	public void purged(Stale stale) {
		jdbc.update("update thr_doc_run set status = 'PURGED', updated_at = now() where id = ? and status in ('DONE', 'FAILED', 'CANCELLED')",
				stale.run());
	}
}
