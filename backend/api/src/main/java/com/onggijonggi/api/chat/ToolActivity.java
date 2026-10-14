package com.onggijonggi.api.chat;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ToolActivity.java
 * Description : 도구 이름·인자를 방에 알릴 활동(종류 코드·대상)으로 바꾼다(이슈 #397). 대상은 인자에서 그 도구의
 *               키 하나만 꺼낸다 — 개정본의 바꿀 문장처럼 다른 인자는 싣지 않는다. 현재 시각처럼 사용자에게
 *               의미 있는 작업이 아닌 도구는 알리지 않는다(empty). 문구는 화면이 종류 코드로 고른다.
 */
record ToolActivity(String kind, String target) {

	/** 대상이 이보다 길면 잘라서 보낸다. */
	static final int MAX_TARGET_LENGTH = 100;

	static Optional<ToolActivity> of(String toolName, String input, ObjectMapper json) {
		return switch (toolName) {
			// 도구 이름은 @Tool 메서드 이름이다(CurrentTimeTool·WebTools·ThreadDocumentTool).
			case "currentDateTime" -> Optional.empty();
			case "webSearch" -> Optional.of(new ToolActivity("WEB_SEARCH", target(input, "query", json)));
			case "webFetch" -> Optional.of(new ToolActivity("WEB_FETCH", target(input, "url", json)));
			case ThreadDocumentTool.NAME ->
				Optional.of(new ToolActivity("DOCUMENT_SEARCH", target(input, "query", json)));
			case ThreadDocumentTool.REVISE_NAME ->
				Optional.of(new ToolActivity("DOCUMENT_REVISE", target(input, "fileName", json)));
			default -> Optional.of(new ToolActivity("TOOL", null));
		};
	}

	private static String target(String input, String key, ObjectMapper json) {
		try {
			JsonNode value = json.readTree(input).get(key);
			if (value == null || !value.isString() || value.asString().isBlank()) {
				return null;
			}
			String text = value.asString().strip();
			return text.length() > MAX_TARGET_LENGTH ? text.substring(0, MAX_TARGET_LENGTH) + "…" : text;
		} catch (RuntimeException unreadable) {
			return null;
		}
	}
}
