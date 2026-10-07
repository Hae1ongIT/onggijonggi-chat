package com.onggijonggi.common.document;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Class Name : ThreadDocumentStates.java
 * Description : 방 문서(thr_doc)의 처리 상태 전이와 ETL 처리 회차(thr_doc_run) 생성 규칙. BFF와 ETL 워커가 같은 조건부
 *               UPDATE를 써서, 그사이 삭제된 문서를 되살리거나 허용하지 않은 전이를 만들지 않는다. 호출자의 트랜잭션 안에서 실행한다.
 */
public final class ThreadDocumentStates {

	/** 허용하는 처리 상태 전이. 등록(UPLOADING→PENDING)과 삭제는 문서 등록·변경 경로가 따로 다룬다. */
	private static final Map<String, Set<String>> TRANSITIONS = Map.of(
			"PENDING", Set.of("PROCESSING"),
			"PROCESSING", Set.of("READY", "FAILED"),
			// 사용자 재처리(#340). 등록 사건이 있는 문서만 호출자가 넘긴다.
			"FAILED", Set.of("PENDING"),
			// 조각이 없어진 READY 문서의 자동 복구(RECOVER 회차)가 실패했다(#348). 사용자에게 "처리 실패"와 다시 처리 버튼을 보인다.
			"READY", Set.of("FAILED"));

	private ThreadDocumentStates() {
	}

	/** expected 상태일 때만 next로 바꾼다. 바꿨으면 true — false면 그사이 상태가 바뀌었거나 삭제된 것이다. */
	public static boolean transition(JdbcTemplate jdbc, UUID document, String expected, String next) {
		if (!TRANSITIONS.getOrDefault(expected, Set.of()).contains(next))
			throw new IllegalArgumentException("허용하지 않은 처리 상태 전이: " + expected + " → " + next);
		return jdbc.update("update thr_doc set status = ?, updated_at = now() where id = ? and status = ?",
				next, document, expected) == 1;
	}

	/** 다음 처리 회차를 PENDING으로 만든다. 진행 중 회차가 이미 있으면 부분 유일 인덱스가 막는다. 만든 회차 번호를 돌려준다. */
	public static int createRun(JdbcTemplate jdbc, UUID document, UUID tenant, UUID thread) {
		Integer seq = jdbc.queryForObject("select coalesce(max(run_seq), 0) + 1 from thr_doc_run where doc_id = ?", Integer.class, document);
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status) values (?, ?, ?, ?, ?, 'PENDING')",
				UUID.randomUUID(), document, tenant, thread, seq);
		return seq;
	}
}
