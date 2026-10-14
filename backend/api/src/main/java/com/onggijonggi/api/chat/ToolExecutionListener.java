package com.onggijonggi.api.chat;

/**
 * Class Name : ToolExecutionListener.java
 * Description : 도구 실행의 시작·결과를 받는 자리(이슈 #390). 진행 표시("웹 검색 중…")와 웹 출처 수집이 이것을
 *               구현해 TurnToolExecutions에 붙인다. 도구 실행 스레드(boundedElastic)에서 불리므로 오래 걸리는 일을
 *               하지 않는다. 여기서 던진 예외는 TurnToolExecutions가 잡아 도구 실행을 깨지 않게 한다.
 */
public interface ToolExecutionListener {

	/** 도구가 실행되기 직전(실행 직전 확인을 통과한 뒤). */
	default void started(String toolName, String input) {
	}

	/** 도구 실행이 끝났을 때 — 성공이든 실패든. */
	default void finished(ToolExecution execution) {
	}
}
