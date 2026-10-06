package com.onggijonggi.api.rag;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : SearchResult.java
 * Description : 방 문서 검색 결과(E·검증 API 공통 계약). 인가 거부는 이 결과가 아니라 검색 전 404로 끝난다.
 *               FOUND는 기준을 통과한 근거가 있음, NO_EVIDENCE는 대상 문서가 없거나 기준을 통과한 청크가 없음,
 *               UNAVAILABLE은 Elasticsearch·임베딩 장애나 모델 불일치로 검색을 끝내지 못함이다 — 장애를 근거 없음으로 숨기지 않는다.
 * @param query 실제로 검색한 문장(다시 썼으면 다시 쓴 문장)
 * @param rewritten 검색 문장을 다시 썼는지
 */
public record SearchResult(Status status, String query, boolean rewritten, List<Chunk> chunks) {

	public enum Status { FOUND, NO_EVIDENCE, UNAVAILABLE }

	/** 근거 청크. loc은 출처 위치(PDF는 page=N, 그 외는 para=N). 점수는 그 채널 기준을 통과했을 때만 있다. */
	public record Chunk(UUID documentId, String fileName, int runSeq, int seq, String loc, String content,
			Double vectorScore, Double keywordScore) { }
}
