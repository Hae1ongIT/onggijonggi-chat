package com.onggijonggi.api.chat;

/**
 * Class Name : ToolExecutionListener.java
 * Description : 도구 실행의 시작·결과를 받는 자리(이슈 #390). 진행 표시("웹 검색 중…", #397)가 이것을
 *               구현해 TurnToolExecutions에 붙인다. 도구 실행 스레드(boundedElastic)에서 불리므로 오래 걸리는 일을
 *               하지 않는다. 여기서 던진 RuntimeException은 TurnToolExecutions가 잡아 로그만 남기고 도구 실행을 깨지
 *               않게 한다(Error는 그대로 올라간다). 그 로그에 예외가 통째로 남으므로 예외 메시지에 도구 인자·결과를 넣지 않는다.
 */
public interface ToolExecutionListener {

	/** 도구가 실행되기 직전. 실행 직전 확인(권한 재확인·승인)이 생기면 그것을 통과한 뒤에 불린다. */
	default void started(String toolName, String input) {
	}

	/** 도구 실행이 끝났을 때 — 성공이든 실패든. */
	default void finished(ToolExecution execution) {
	}
}
