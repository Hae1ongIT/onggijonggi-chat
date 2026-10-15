package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Class Name : TurnToolExecutionsTest.java
 * Description : TurnToolExecutions가 도구를 감싸도 정의가 그대로고, 실행을 턴 단위로 기록하며, 알림 쪽 고장이 도구 실행·다른
 *               리스너·도구의 원래 예외를 깨지 않는지, 로그에 인자·결과가 남지 않는지 검증한다(이슈 #390). 실제 도구 루프를 지나는
 *               경우는 LlmChatStreamServiceTest가 본다.
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

	/** 앞 리스너가 던져도 뒤 리스너는 시작·끝을 모두 받고, 끝 알림 때는 기록이 이미 들어 있다. */
	@Test
	void laterListenersStillRunAndSeeTheRecordAlreadyStored() {
		List<String> notified = new ArrayList<>();
		AtomicReference<TurnToolExecutions> self = new AtomicReference<>();
		TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace", List.of(
				new ToolExecutionListener() {
					@Override
					public void started(String toolName, String input) {
						throw new IllegalStateException("앞 리스너 고장");
					}

					@Override
					public void finished(ToolExecution execution) {
						throw new IllegalStateException("앞 리스너 고장");
					}
				},
				new ToolExecutionListener() {
					@Override
					public void started(String toolName, String input) {
						notified.add("started");
					}

					@Override
					public void finished(ToolExecution execution) {
						notified.add("finished:recorded=" + self.get().executions().size());
					}
				}));
		self.set(executions);

		executions.wrap(new ToolCallback[] { ECHO })[0].call("x");

		assertThat(notified).containsExactly("started", "finished:recorded=1");
	}

	/** 도구가 실패하고 끝 알림 리스너까지 던져도, 올라가는 것은 도구의 원래 예외이고 기록은 한 번이다. */
	@Test
	void aFailingToolRethrowsItsOwnExceptionEvenIfTheFinishedListenerThrows() {
		IllegalStateException original = new IllegalStateException("도구 고장");
		TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace",
				List.of(new ToolExecutionListener() {
					@Override
					public void finished(ToolExecution execution) {
						throw new IllegalArgumentException("알림 고장");
					}
				}));

		assertThatThrownBy(() -> executions.wrap(new ToolCallback[] { tool(input -> {
			throw original;
		}) })[0].call("x")).isSameAs(original);
		assertThat(executions.executions()).singleElement()
				.satisfies(execution -> assertThat(execution.failure()).isEqualTo("IllegalStateException"));
	}

	/** 로그에는 도구 이름·걸린 시간·성공 여부만 남고, 인자·결과(사내 내용이 섞일 수 있음)는 남지 않는다. */
	@Test
	void logsNeitherTheArgumentsNorTheResult() {
		Logger logger = (Logger) LoggerFactory.getLogger(TurnToolExecutions.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
		try {
			TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace",
					List.of(new ToolExecutionListener() {
						@Override
						public void finished(ToolExecution execution) {
							throw new IllegalStateException("알림 고장");
						}
					}));
			executions.wrap(new ToolCallback[] { tool(input -> "비밀결과") })[0].call("{\"q\":\"비밀규정\"}");

			assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
					.anySatisfy(message -> assertThat(message).contains("tool=echo").contains("result=ok"))
					.allSatisfy(message -> assertThat(message).doesNotContain("비밀규정").doesNotContain("비밀결과"));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	/** 도구가 Error를 던져도 기록과 끝 알림이 빠지지 않고, Error는 그대로 올라간다. */
	@Test
	void recordsAndFinishesEvenWhenTheToolThrowsAnError() {
		List<String> notified = new ArrayList<>();
		TurnToolExecutions executions = new TurnToolExecutions(UUID.randomUUID(), "trace",
				List.of(new ToolExecutionListener() {
					@Override
					public void started(String toolName, String input) {
						notified.add("started");
					}

					@Override
					public void finished(ToolExecution execution) {
						notified.add("finished:" + execution.failure());
					}
				}));
		ToolCallback broken = tool(input -> {
			throw new StackOverflowError();
		});

		assertThatThrownBy(() -> executions.wrap(new ToolCallback[] { broken })[0].call("x"))
				.isInstanceOf(StackOverflowError.class);
		assertThat(notified).containsExactly("started", "finished:StackOverflowError");
		assertThat(executions.executions()).singleElement().satisfies(execution -> assertThat(execution.succeeded()).isFalse());
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

	/** 정의는 DEFINITION이고 실행은 body인 도구. */
	private static ToolCallback tool(Function<String, String> body) {
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return DEFINITION;
			}

			@Override
			public String call(String toolInput) {
				return body.apply(toolInput);
			}
		};
	}
}
