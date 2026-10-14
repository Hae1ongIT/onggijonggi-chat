package com.onggijonggi.api.rag;

/**
 * Class Name : RagUnavailableException.java
 * Description : 검색을 끝내지 못한 장애(Elasticsearch·임베딩 오류, 모델 불일치, 설정 없음). 검색 결과의 UNAVAILABLE과 그 사유로 바뀌며
 *               "근거 없음"으로 숨기지 않는다. 메시지에는 질문·청크 본문을 넣지 않는다(로그에 남는다).
 */
public class RagUnavailableException extends RuntimeException {

	private final SearchResult.Reason reason;

	/** 외부 호출(Elasticsearch·임베딩) 장애. */
	public RagUnavailableException(String message) {
		this(SearchResult.Reason.BACKEND_ERROR, message, null);
	}

	/** 외부 호출(Elasticsearch·임베딩) 장애. */
	public RagUnavailableException(String message, Throwable cause) {
		this(SearchResult.Reason.BACKEND_ERROR, message, cause);
	}

	public RagUnavailableException(SearchResult.Reason reason, String message) {
		this(reason, message, null);
	}

	private RagUnavailableException(SearchResult.Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public SearchResult.Reason reason() {
		return reason;
	}
}
