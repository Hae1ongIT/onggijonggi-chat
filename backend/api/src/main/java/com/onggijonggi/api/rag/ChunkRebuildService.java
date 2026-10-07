package com.onggijonggi.api.rag;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : ChunkRebuildService.java
 * Description : 운영자 일괄 재처리 요청(#348)을 남기고 진행 상황을 읽는다. 대상 선정과 회차 생성은 ETL이 한다 — BFF는 ETL의 청킹 설정을
 *               모르므로 "설정이 바뀐 문서"를 여기서 고를 수 없다. 진행 중인 요청(대기 중이거나 만든 회차가 아직 처리 중)이 있으면
 *               새 요청을 받지 않는다. 블로킹 JDBC라 호출자가 boundedElastic에서 부른다.
 */
@Service
public class ChunkRebuildService {

	public enum Scope { ALL, OUTDATED }

	/** 요청과 진행 상황. targets는 ETL이 회차를 만들기 전(PENDING)에는 null이다. */
	public record Rebuild(UUID id, Scope scope, String status, String requestedBy, Instant requestedAt, Instant completedAt,
			Integer targets, int remaining, int failed) { }

	/** 최근 요청 목록의 길이. */
	static final int RECENT = 10;

	private static final String SELECT = "select r.id, r.kind, r.status, r.req_subj, r.created_at, r.completed_at, r.trg_cnt,"
			+ " (select count(*) from thr_doc_run x where x.rbl_id = r.id and x.status in ('PENDING', 'RUNNING')),"
			// 실패 회차는 정리 작업이 PURGED로 바꿔도 사유 코드(err)가 남는다. 성공한 회차는 err가 비어 있다.
			+ " (select count(*) from thr_doc_run x where x.rbl_id = r.id and (x.status = 'FAILED' or (x.status = 'PURGED' and x.err is not null)))"
			+ " from doc_rbl r";
	/** 진행 중인 요청: ETL이 아직 집지 않았거나, 만든 회차가 아직 처리 중이다. */
	private static final String IN_PROGRESS = " where r.status = 'PENDING' or exists (select 1 from thr_doc_run x where x.rbl_id = r.id"
			+ " and x.status in ('PENDING', 'RUNNING'))";

	private final JdbcTemplate jdbc;

	public ChunkRebuildService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 트랜잭션으로 묶지 않는다 — 동시 요청의 유일 인덱스 위반 뒤에 진행 중 요청을 다시 읽어야 하는데, PostgreSQL은 오류가 난 트랜잭션의
	 * 다음 문장을 거부한다. 확인과 삽입 사이의 경합은 대기 중 요청 유일 인덱스가 막는다.
	 */
	public Rebuild request(Scope scope, String requester) {
		Optional<Rebuild> running = first(SELECT + IN_PROGRESS + " order by r.created_at desc limit 1");
		if (running.isPresent()) throw new ChunkRebuildInProgressException(running.get().id());
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("insert into doc_rbl(id, kind, status, req_subj) values (?, ?, 'PENDING', ?)", id, scope.name(), requester);
		} catch (DuplicateKeyException concurrent) {
			// 같은 순간 다른 요청이 먼저 들어갔다(대기 중 요청은 하나뿐 — uq_doc_rbl_pending).
			UUID other = jdbc.queryForObject("select id from doc_rbl where status = 'PENDING'", UUID.class);
			throw new ChunkRebuildInProgressException(other);
		}
		return find(id).orElseThrow();
	}

	@Transactional(readOnly = true)
	public Optional<Rebuild> find(UUID id) {
		return first(SELECT + " where r.id = ?", id);
	}

	@Transactional(readOnly = true)
	public List<Rebuild> recent() {
		return jdbc.query(SELECT + " order by r.created_at desc limit " + RECENT, (rs, row) -> map(rs));
	}

	private Optional<Rebuild> first(String sql, Object... args) {
		return jdbc.query(sql, (rs, row) -> map(rs), args).stream().findFirst();
	}

	private static Rebuild map(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp completed = rs.getTimestamp(6);
		return new Rebuild(rs.getObject(1, UUID.class), Scope.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4),
				rs.getTimestamp(5).toInstant(), completed == null ? null : completed.toInstant(), (Integer) rs.getObject(7),
				rs.getInt(8), rs.getInt(9));
	}
}
