package com.onggijonggi.api.rag;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : SearchResult.java
 * Description : 방 문서 검색 결과(E·검증 API 공통 계약). 인가 거부는 이 결과가 아니라 검색 전 404로 끝난다.
 *               FOUND는 기준을 통과한 근거가 있음, NO_EVIDENCE는 대상 문서가 없거나 기준을 통과한 청크가 없음,
 *               UNAVAILABLE은 검색을 끝내지 못함이다 — 장애를 근거 없음으로 숨기지 않는다. reason은 화면 안내를 나누는 사유다
 *               (FOUND면 null). 확인 API는 UNAVAILABLE도 200으로 돌려준다(장애여도 답변은 계속해야 하는 E와 같은 계약).
 * @param query 실제로 검색한 문장(다시 썼으면 다시 쓴 문장). 장애·대상 0건이면 원래 질문
 * @param rewritten 검색 문장을 다시 썼는지
 */
public record SearchResult(Status status, Reason reason, String query, boolean rewritten, List<Chunk> chunks) {

	public enum Status { FOUND, NO_EVIDENCE, UNAVAILABLE }

	/**
	 * 근거가 없거나 검색을 끝내지 못한 사유. 값을 늘리면 소비자 분기가 바뀌므로 처음부터 정해 둔다.
	 * NO_PINNED_DOCUMENTS·NO_MATCH는 NO_EVIDENCE, 나머지는 UNAVAILABLE이다. MODEL_MISMATCH는 기다려도 풀리지 않는다(문서 재처리 필요),
	 * NOT_CONFIGURED는 운영 설정 문제, BACKEND_ERROR·OVERLOADED는 잠시 뒤 다시 시도할 수 있다.
	 */
	public enum Reason { NO_PINNED_DOCUMENTS, NO_MATCH, NOT_CONFIGURED, MODEL_MISMATCH, BACKEND_ERROR, OVERLOADED }

	/**
	 * 근거 청크. chunkId(문서:회차:순번)는 색인의 청크 ID 그대로다(ChunkIndexContract.chunkId) — 재처리하면 회차가 바뀌고 이전 회차 청크는 지워지므로, 출처를 저장하는
	 * 쪽은 본문·위치·파일 이름 스냅샷을 함께 저장한다. 원본은 문서 ID로 연다(회차와 무관). loc은 PDF면 page=N, 그 외는 para=N.
	 * 점수는 그 채널 기준을 통과했을 때만 있고(아니면 null) 평가·디버그용이다 — 척도가 서로 달라(벡터는 코사인, 키워드는 BM25)
	 * 화면 표시·판정에 쓰지 않는다.
	 */
	public record Chunk(String chunkId, UUID documentId, String fileName, int runSeq, int seq, String loc, String content,
			Double vectorScore, Double keywordScore) { }

	static SearchResult found(String query, boolean rewritten, List<Chunk> chunks) {
		return new SearchResult(Status.FOUND, null, query, rewritten, chunks);
	}

	static SearchResult noEvidence(Reason reason, String query, boolean rewritten) {
		return new SearchResult(Status.NO_EVIDENCE, reason, query, rewritten, List.of());
	}

	static SearchResult unavailable(Reason reason, String question) {
		return new SearchResult(Status.UNAVAILABLE, reason, question, false, List.of());
	}
}
