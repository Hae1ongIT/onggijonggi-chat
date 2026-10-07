package com.onggijonggi.etl;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Class Name : RebuildStore.java
 * Description : READY 문서의 다시 만들기 회차(REBUILD·RECOVER)를 만들고 운영자 일괄 재처리 요청(doc_rbl)을 처리한다(#348).
 *               회차는 "READY이고 진행 중 회차가 없는 문서"에만 만든다 — 진행 중 회차가 있으면 그 처리가 끝난 뒤의 상태를 따른다.
 *               새 회차는 처리가 끝나기 전까지 검색에 쓰이지 않는다(검색은 문서별 가장 큰 DONE 회차를 본다).
 */
@Component
public class RebuildStore {

	/** 문서의 현재(가장 큰 DONE) 회차. 자동 복구가 ES의 청크 수와 대조한다. chunks는 그 회차에 적재·대조한 청크 수. */
	public record CurrentRun(UUID document, int runSeq, int chunks) { }

	/** 처리할 운영자 요청. kind는 ALL(READY 전체)·OUTDATED(설정이 바뀐 문서) — BFF ChunkRebuildService.Scope와 같은 글자다. */
	public record Request(UUID id, String kind) { }

	/** 처리한 운영자 요청과 만든 회차 수. */
	public record HandledRequest(Request request, int targets) { }

	static final String OUTDATED_SCOPE = "OUTDATED";

	/** 다시 만들 수 있는 문서와 그 현재(가장 큰 DONE) 회차. 진행 중 회차가 있는 문서는 빠진다. "현재 회차"는 BFF 검색 범위
	 *  (ThreadDocumentService.searchScope)와 같은 정의다 — 한쪽을 바꾸면 다른 쪽도 맞춘다. */
	private static final String CANDIDATES = " from thr_doc d join thr_doc_run c on c.doc_id = d.id and c.status = 'DONE'"
			+ " and c.run_seq = (select max(m.run_seq) from thr_doc_run m where m.doc_id = d.id and m.status = 'DONE')"
			+ " where d.status = 'READY'"
			+ " and not exists (select 1 from thr_doc_run a where a.doc_id = d.id and a.status in ('PENDING', 'RUNNING'))";
	/** 현재 회차의 처리 설정이 지금 ETL 설정과 다르다(임베딩 모델·차원·청킹). */
	private static final String OUTDATED = " and (c.emb_mdl is distinct from ? or c.emb_dim is distinct from ? or c.chnk_cnf is distinct from ?)";
	/** 현재 회차 뒤에 실패한 다시 만들기 회차가 없다(정리돼 PURGED가 된 것 포함 — 현재 회차보다 큰 PURGED는 실패·취소뿐이다). */
	private static final String NOT_FAILED_SINCE = " and not exists (select 1 from thr_doc_run f where f.doc_id = d.id and f.run_seq > c.run_seq"
			+ " and f.run_kind = 'REBUILD' and f.status in ('FAILED', 'PURGED'))";
	/** 다시 만들기 회차를 만든다. 동시에 다른 경로가 회차를 만들었으면(유일 인덱스) 그 문서는 건너뛴다. */
	private static final String INSERT = "insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, run_kind, rbl_id)"
			+ " select gen_random_uuid(), d.id, d.tnn_id, d.thr_id,"
			+ " (select max(x.run_seq) + 1 from thr_doc_run x where x.doc_id = d.id), 'PENDING', cast(? as varchar(16)), cast(? as uuid)";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public RebuildStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	/**
	 * 설정이 바뀐 문서에 REBUILD 회차를 최대 limit개 만든다(자동). 현재 회차 뒤에 실패한 다시 만들기가 있는 문서는 뺀다 — 원본이
	 * 없어 금방 실패하는 문서를 주기마다 다시 잡아 같은 앞쪽 문서만 돌고 뒤 문서가 영영 차례를 못 받는 일을 막는다. 그런 문서는
	 * 운영자 요청(OUTDATED)으로 다시 시도한다. 만든 수를 돌려준다 — limit과 같으면 남은 문서가 있을 수 있다.
	 */
	public int queueOutdated(String model, int dimensions, String chunking, int limit) {
		return jdbc.update(INSERT + CANDIDATES + OUTDATED + NOT_FAILED_SINCE + " order by d.id limit ? on conflict do nothing",
				RunStore.REBUILD, null, model, dimensions, chunking, limit);
	}

	/** 처리를 기다리거나 처리 중인 다시 만들기 회차 수. 자동 경로가 워커보다 빨리 쌓지 않게 본다. */
	public int backlog() {
		Integer count = jdbc.queryForObject("select count(*) from thr_doc_run where status in ('PENDING', 'RUNNING') and run_kind <> 'INGEST'",
				Integer.class);
		return count == null ? 0 : count;
	}

	/** 현재 회차를 문서 ID 순으로 after 다음부터 limit개. 자동 복구가 나눠서 대조한다. */
	public List<CurrentRun> currentRuns(UUID after, int limit) {
		return jdbc.query("select d.id, c.run_seq, c.chunk_cnt" + CANDIDATES + " and c.chunk_cnt is not null"
				+ (after == null ? "" : " and d.id > ?") + " order by d.id limit ?",
				(rs, row) -> new CurrentRun(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)),
				after == null ? new Object[] {limit} : new Object[] {after, limit});
	}

	/**
	 * 청크가 빠진 문서에 RECOVER 회차를 만든다. 대조한 뒤 그 문서의 현재 회차가 바뀌었으면(그사이 처리가 끝남) 만들지 않는다 —
	 * 대조한 회차는 이미 정리 대상이라 청크가 없어 보였을 수 있다. 만들었으면 true.
	 */
	public boolean queueRecover(CurrentRun run) {
		return jdbc.update(INSERT + CANDIDATES + " and d.id = ? and c.run_seq = ? on conflict do nothing",
				RunStore.RECOVER, null, run.document(), run.runSeq()) == 1;
	}

	/**
	 * 대기 중 운영자 요청 하나를 처리한다: 대상 문서마다 REBUILD 회차를 만들고 요청을 완료로 표시한다. 한 트랜잭션이라 요청이 완료되면
	 * 회차도 모두 있다. 처리한 요청과 대상 수를 돌려주고, 대기 요청이 없으면 null.
	 */
	public HandledRequest handleNextRequest(String model, int dimensions, String chunking) {
		return transactions.execute(tx -> {
			var requests = jdbc.query("select id, kind from doc_rbl where status = 'PENDING' order by created_at limit 1 for update skip locked",
					(rs, row) -> new Request(rs.getObject(1, UUID.class), rs.getString(2)));
			if (requests.isEmpty()) return null;
			Request request = requests.get(0);
			int targets = OUTDATED_SCOPE.equals(request.kind())
					? jdbc.update(INSERT + CANDIDATES + OUTDATED + " on conflict do nothing", RunStore.REBUILD, request.id(), model, dimensions, chunking)
					: jdbc.update(INSERT + CANDIDATES + " on conflict do nothing", RunStore.REBUILD, request.id());
			jdbc.update("update doc_rbl set status = 'COMPLETED', trg_cnt = ?, completed_at = now() where id = ?", targets, request.id());
			return new HandledRequest(request, targets);
		});
	}

}
