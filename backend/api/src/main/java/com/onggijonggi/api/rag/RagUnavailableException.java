package com.onggijonggi.api.rag;

/**
 * Class Name : RagUnavailableException.java
 * Description : 검색을 끝내지 못한 장애(Elasticsearch·임베딩 오류, 모델 불일치, 설정 없음). 검색 결과의 UNAVAILABLE로 바뀌며
 *               "근거 없음"으로 숨기지 않는다. 메시지에는 질문·청크 본문을 넣지 않는다(로그에 남는다).
 */
public class RagUnavailableException extends RuntimeException {

	public RagUnavailableException(String message) {
		super(message);
	}

	public RagUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
