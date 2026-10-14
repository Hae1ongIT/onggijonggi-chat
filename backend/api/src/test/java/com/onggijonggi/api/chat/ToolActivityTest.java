package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ToolActivityTest.java
 * Description : 도구 이름·인자가 방에 알릴 종류 코드·대상으로 바뀌는지 검증한다(이슈 #397).
 */
class ToolActivityTest {

	private final ObjectMapper json = JsonMapper.builder().build();

	@Test
	void mapsEachToolToItsKindAndTarget() {
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"서울 날씨\"}", json))
				.contains(new ToolActivity("WEB_SEARCH", "서울 날씨"));
		assertThat(ToolActivity.of("webFetch", "{\"url\":\"https://example.org/a\"}", json))
				.contains(new ToolActivity("WEB_FETCH", "https://example.org/a"));
		assertThat(ToolActivity.of(ThreadDocumentTool.NAME, "{\"query\":\"연차 신청\"}", json))
				.contains(new ToolActivity("DOCUMENT_SEARCH", "연차 신청"));
		assertThat(ToolActivity.of("unknownTool", "{\"x\":1}", json)).contains(new ToolActivity("TOOL", null));
	}

	@Test
	void doesNotAnnounceTheCurrentTimeTool() {
		assertThat(ToolActivity.of("currentDateTime", "{}", json)).isEmpty();
	}

	/** 개정본은 파일 이름만 싣는다 — 바꿀 문장(edits)은 싣지 않는다. */
	@Test
	void revisionCarriesOnlyTheFileName() {
		assertThat(ToolActivity.of(ThreadDocumentTool.REVISE_NAME,
				"{\"fileName\":\"규정.docx\",\"edits\":[{\"before\":\"12시간\",\"after\":\"18시간\"}]}", json))
				.contains(new ToolActivity("DOCUMENT_REVISE", "규정.docx"));
	}

	@Test
	void cutsLongTargetsAndToleratesBrokenInput() {
		String longQuery = "가".repeat(150);
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"" + longQuery + "\"}", json).orElseThrow().target())
				.isEqualTo("가".repeat(ToolActivity.MAX_TARGET_LENGTH) + "…");
		assertThat(ToolActivity.of("webSearch", "{깨진", json)).contains(new ToolActivity("WEB_SEARCH", null));
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"  \"}", json)).contains(new ToolActivity("WEB_SEARCH", null));
	}
}
