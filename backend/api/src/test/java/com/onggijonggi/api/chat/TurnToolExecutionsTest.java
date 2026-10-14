package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Class Name : TurnToolExecutionsTest.java
 * Description : TurnToolExecutions가 도구를 감싸도 정의가 그대로고, 실행을 턴 단위로 기록하며, 알림 쪽 고장이 도구 실행을 깨지
 *               않는지 검증한다(이슈 #390). 실제 도구 루프를 지나는 경우는 LlmChatStreamServiceTest가 본다.
 */
class TurnToolExecutionsTest {

	private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
			.name("echo").description("받은 걸 돌려준다").inputSchema("{\"type\":\"object\"}").build();

	private static final ToolCallback ECHO = new ToolCallback() {
		@Override
		public ToolDefinition getToolDefinition() {
			return DEFINITION;
		}

		@Override
		public String call(String toolInput) {
			return "echo:" + toolInput;
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return "echo:" + toolInput + ":" + toolContext.getContext().get("k");
		}
	};

	@Test
	void keepsTheToolDefinitionAndPassesTheToolContext() {
		TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace", List.of());

		ToolCallback wrapped = executions.wrap(new ToolCallback[] { ECHO })[0];

		assertThat(wrapped.getToolDefinition()).isSameAs(DEFINITION);
		assertThat(wrapped.call("{}", new ToolContext(Map.of("k", "v")))).isEqualTo("echo:{}:v");
		assertThat(wrapped.call("{}")).isEqualTo("echo:{}");
		assertThat(executions.executions()).extracting(ToolExecution::output).containsExactly("echo:{}:v", "echo:{}");
	}

	@Test
	void aBrokenListenerDoesNotBreakTheTool() {
		TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace",
				List.of(new ToolExecutionListener() {
					@Override
					public void started(String toolName, String input) {
						throw new IllegalStateException("알림 고장");
					}

					@Override
					public void finished(ToolExecution execution) {
						throw new IllegalStateException("알림 고장");
					}
				}));

		assertThat(executions.wrap(new ToolCallback[] { ECHO })[0].call("x")).isEqualTo("echo:x");
		assertThat(executions.executions()).singleElement().satisfies(execution -> assertThat(execution.succeeded()).isTrue());
	}

	@Test
	void eachTurnKeepsItsOwnExecutions() {
		TurnToolExecutions first = new TurnToolExecutions(UUID.randomUUID(), "a", List.of());
		TurnToolExecutions second = new TurnToolExecutions(UUID.randomUUID(), "b", List.of());

		first.wrap(new ToolCallback[] { ECHO })[0].call("1");
		second.wrap(new ToolCallback[] { ECHO })[0].call("2");

		assertThat(first.executions()).extracting(ToolExecution::input).containsExactly("1");
		assertThat(second.executions()).extracting(ToolExecution::input).containsExactly("2");
	}
}
