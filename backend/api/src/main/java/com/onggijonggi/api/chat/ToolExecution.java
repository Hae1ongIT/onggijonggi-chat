package com.onggijonggi.api.chat;

import java.time.Duration;

/**
 * Class Name : ToolExecution.java
 * Description : AI 턴에서 도구를 한 번 실행한 기록(이슈 #390). TurnToolExecutions가 도구 호출마다 만든다.
 *               메모리에만 두고 저장하지 않는다 — 턴이 끝나면 함께 사라진다.
 * @param toolName 모델이 부른 도구 이름
 * @param input 모델이 넘긴 인자(JSON 문자열)
 * @param output 모델에 돌려준 결과. 실패했으면 null
 * @param failure 실패했으면 예외 클래스의 단순 이름, 성공했으면 null
 * @param elapsed 도구가 실행된 시간
 */
public record ToolExecution(String toolName, String input, String output, String failure, Duration elapsed) {

	boolean succeeded() {
		return failure == null;
	}
}
