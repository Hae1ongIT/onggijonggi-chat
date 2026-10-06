package com.onggijonggi.api.chat;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : ThreadDocumentScope.java
 * Description : 방 문서 검색(#344) 범위. 방 접근 확인을 통과한 뒤 서버가 DB에서 읽은 값만 담는다 — 요청의 문서 ID를 믿지 않는다.
 *               targets는 고정·검색 준비 완료(READY) 문서와 각 문서의 현재 완료 회차다. 회차까지 담는 이유는 재처리 직후 이전 회차
 *               청크가 정리 작업이 지울 때까지 색인에 남아 있기 때문이다. 비어 있으면 검색하지 않는다(범위를 넓히지 않는다).
 */
public record ThreadDocumentScope(UUID tenant, ThreadScopeFilter threads, List<Target> targets) {

	/** 검색 대상 문서 하나. runSeq는 그 문서의 현재 완료(DONE) 회차, embeddingModel·dimensions는 그 회차를 임베딩한 모델이다. */
	public record Target(UUID document, String fileName, int runSeq, String embeddingModel, int dimensions) { }
}
