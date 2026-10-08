package com.onggijonggi.etl;

import com.onggijonggi.common.document.TagPrompt;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Class Name : TagStore.java
 * Description : 처리 회차별 태그(thr_doc_tag, #362)의 대상 조회와 저장. 태깅 대상은 READY 문서의 현재 회차(가장 큰 DONE 회차 — 검색
 *               범위 ThreadDocumentService.searchScope와 같은 정의) 중 태그가 없거나, 태깅 설정이 지금과 다르거나, 실패해 다시 시도할 때가
 *               된 것이다. 미분류(DONE)는 설정이 바뀌기 전에는 다시 하지 않는다.
 *               이미 태그가 있는 회차의 재태깅이 실패하면 기존 태그를 지우지 않고 간격만 둔다 — 검색은 이전 태그로 계속된다.
 */
@Component
public class TagStore {

	/** 태깅할 회차와 원본을 읽는 데 필요한 문서 snapshot. */
	public record Target(UUID document, UUID tenant, UUID thread, int runSeq, String fileName, String digest, UUID sourceAttempt) {

		/** 원본 읽기(SourceReader)가 쓰는 처리 작업 모양. 회차 ID·시도 횟수는 쓰지 않는다. */
		RunStore.Job job() {
			return new RunStore.Job(null, document, tenant, thread, runSeq, 0, fileName, digest, sourceAttempt);
		}
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public TagStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	/** 태깅할 회차를 최대 limit개. 태그 없음 → 설정이 바뀐 태그 → 실패 재시도 순이다. */
	public List<Target> targets(String fingerprint, int limit) {
		return jdbc.query("select d.id, d.tnn_id, d.thr_id, c.run_seq, d.file_name, d.src_key, d.src_att_id"
				+ " from thr_doc d join thr_doc_run c on c.doc_id = d.id and c.status = 'DONE'"
				+ " and c.run_seq = (select max(m.run_seq) from thr_doc_run m where m.doc_id = d.id and m.status = 'DONE')"
				+ " left join thr_doc_tag t on t.doc_id = d.id and t.run_seq = c.run_seq"
				+ " where d.status = 'READY' and (t.id is null or (t.tag_cnf <> ? and t.next_at <= now())"
				+ " or (t.status = 'FAILED' and t.next_at <= now()))"
				+ " order by case when t.id is null then 0 when t.status = 'DONE' then 1 else 2 end, c.updated_at, d.id limit ?",
				(rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
						rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class)),
				fingerprint, limit);
	}

	/**
	 * 태그를 확정한다. 그 회차가 아직 현재 처리 결과로 남아 있을 때만(회차 DONE, 문서 READY) — 태깅하는 동안 문서가 지워지거나 회차가
	 * 정리됐으면 남기지 않고 false를 돌려준다(호출자가 태그 색인에 쓴 문서를 지운다).
	 */
	public boolean saveDone(Target target, TagPrompt.Tags tags, String fingerprint) {
		Boolean saved = transactions.execute(tx -> {
			if (jdbc.queryForList("select d.id from thr_doc d join thr_doc_run r on r.doc_id = d.id and r.run_seq = ? and r.status = 'DONE'"
					+ " where d.id = ? and d.status = 'READY' for share of d, r", target.runSeq(), target.document()).isEmpty())
				return false;
			jdbc.update(con -> {
				PreparedStatement statement = con.prepareStatement("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, ctg, kyw, smm, tag_cnf)"
						+ " values (?, ?, ?, ?, ?, 'DONE', ?, ?, ?, ?) on conflict (doc_id, run_seq) do update set status = 'DONE', ctg = excluded.ctg,"
						+ " kyw = excluded.kyw, smm = excluded.smm, tag_cnf = excluded.tag_cnf, att_cnt = 0, err = null, next_at = now(), updated_at = now()");
				Array keywords = con.createArrayOf("text", tags.keywords().toArray());
				statement.setObject(1, UUID.randomUUID());
				statement.setObject(2, target.document());
				statement.setObject(3, target.tenant());
				statement.setObject(4, target.thread());
				statement.setInt(5, target.runSeq());
				statement.setString(6, tags.category());
				statement.setArray(7, keywords);
				statement.setString(8, tags.summary());
				statement.setString(9, fingerprint);
				return statement;
			});
			return true;
		});
		return Boolean.TRUE.equals(saved);
	}

	/**
	 * 태깅 실패를 남기고 retryDelay 뒤 다시 시도하게 한다. 이미 태그가 있는 회차(설정 변경 재태깅 실패)는 태그를 지우지 않고 간격만 둔다.
	 * 그 회차가 지워졌으면 아무것도 남기지 않는다.
	 */
	public void saveFailed(Target target, String code, String fingerprint, Duration retryDelay) {
		transactions.executeWithoutResult(tx -> {
			if (jdbc.queryForList("select id from thr_doc_run where doc_id = ? and run_seq = ? and status = 'DONE'", target.document(), target.runSeq())
					.isEmpty())
				return;
			jdbc.update("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, tag_cnf, att_cnt, err, next_at)"
					+ " values (?, ?, ?, ?, ?, 'FAILED', ?, 1, ?, now() + ? * interval '1 millisecond')"
					+ " on conflict (doc_id, run_seq) do update set att_cnt = thr_doc_tag.att_cnt + 1, err = excluded.err,"
					+ " next_at = excluded.next_at, updated_at = now(),"
					// 실패만 있던 행은 지금 설정 지문으로 맞춘다. 태그가 있는 행(DONE)은 이전 지문을 그대로 둬 다음 간격 뒤 다시 대상이 된다.
					+ " tag_cnf = case when thr_doc_tag.status = 'FAILED' then excluded.tag_cnf else thr_doc_tag.tag_cnf end",
					UUID.randomUUID(), target.document(), target.tenant(), target.thread(), target.runSeq(), fingerprint, code, retryDelay.toMillis());
		});
	}

	/** 회차 정리(RunSweeper) 때 그 회차의 태그를 지운다. 멱등이다. */
	public void delete(UUID document, int runSeq) {
		jdbc.update("delete from thr_doc_tag where doc_id = ? and run_seq = ?", document, runSeq);
	}
}
