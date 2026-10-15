package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.onggijonggi.api.web.WebTools;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * Class Name : LlmChatStreamServiceTest.java
 * Description : LlmChatStreamService가 모델에 넘기는 Prompt에 현재 시각 도구와 발화자의 시간대(toolContext)가 실리는지 검증한다.
 *               도구 실행은 Spring AI 도구 루프가 맡으므로 실린 도구를 직접 불러 결과를 보고, 도구를 부르는 모델로는 루프 안에서
 *               도구가 턴의 공통 지점(TurnToolExecutions)을 지나는지 본다(이슈 #390).
 */
class LlmChatStreamServiceTest {

	private final AtomicReference<Prompt> received = new AtomicReference<>();
	private final ChatModel model = new ChatModel() {

		/** ChatClient는 모델 기본 옵션이 도구 옵션일 때만 도구를 싣는다. 실제 OpenAiChatModel의 옵션(OpenAiChatOptions)이 그렇다. */
		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			received.set(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("답"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}
	};

	/** 모델에 넘어간 Prompt에 현재 시각 도구와 발화자의 시간대가 함께 실린다. 도구 실행은 모델 구현(ToolCallingManager)이 맡는다. */
	@Test
	void attachesTheCurrentTimeToolWithTheSendersTimeZone() {
		CurrentTimeTool tool = new CurrentTimeTool(Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneOffset.UTC),
				ZoneId.of("Asia/Seoul"));
		// 웹 도구는 꺼진 것으로 둔다(mock의 enabled()는 false). 웹 도구 연결은 web.LlmChatStreamServiceWebToolsTest가 본다.
		LlmChatStreamService service = new LlmChatStreamService(ChatClient.builder(model), tool, mock(WebTools.class));

		service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model",
				List.of(new ChatMessage("user", "지금 몇 시야")), "America/New_York")).blockLast();

		ToolCallingChatOptions options = (ToolCallingChatOptions) received.get().getOptions();
		assertThat(options.getToolContext()).containsEntry(CurrentTimeTool.TIME_ZONE, ZoneId.of("America/New_York"));
		ToolCallback callback = options.getToolCallbacks().stream()
				.filter(candidate -> candidate.getToolDefinition().name().equals("currentDateTime"))
				.findFirst().orElseThrow();
		assertThat(callback.call("{}", new ToolContext(options.getToolContext())))
				.contains("2026-10-07T02:00:00-04:00[America/New_York] WEDNESDAY");
	}

	/** 요청에 방 문서 검색 도구가 있으면 모델에 싣고, 모델이 부르면 그 검색어로 턴의 검색을 실행한다. 없으면 싣지 않는다. */
	@Test
	void attachesTheDocumentToolOnlyWhenTheTurnHasOne() {
		AtomicReference<String> searched = new AtomicReference<>();
		AtomicReference<String> revised = new AtomicReference<>();
		LlmChatStreamService service = new LlmChatStreamService(ChatClient.builder(model), new CurrentTimeTool("Asia/Seoul"),
				mock(WebTools.class));
		List<ChatMessage> messages = List.of(new ChatMessage("user", "연차는 며칠 전에 신청해?"));

		service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model", messages, null,
				new ThreadDocumentTool(query -> {
					searched.set(query);
					return "근거";
				}, (fileName, edits) -> {
					revised.set(fileName + ":" + edits);
					return "등록함";
				}))).blockLast();

		ToolCallback callback = ((ToolCallingChatOptions) received.get().getOptions()).getToolCallbacks().stream()
				.filter(candidate -> candidate.getToolDefinition().name().equals(ThreadDocumentTool.NAME))
				.findFirst().orElseThrow();
		assertThat(callback.call("{\"query\":\"연차 신청 기한\"}")).contains("근거");
		assertThat(searched).hasValue("연차 신청 기한");
		// 개정본 도구는 수정 목록(before·after)을 받아 그대로 넘긴다.
		ToolCallback revise = ((ToolCallingChatOptions) received.get().getOptions()).getToolCallbacks().stream()
				.filter(candidate -> candidate.getToolDefinition().name().equals(ThreadDocumentTool.REVISE_NAME))
				.findFirst().orElseThrow();
		assertThat(revise.getToolDefinition().inputSchema()).contains("\"before\"").contains("\"after\"").contains("원래 문장");
		assertThat(revise.call("{\"fileName\":\"규정.docx\",\"edits\":[{\"before\":\"12시간\",\"after\":\"18시간\"}]}")).contains("등록함");
		assertThat(revised).hasValue("규정.docx:[Edit[before=12시간, after=18시간]]");

		service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model", messages, null)).blockLast();
		assertThat(((ToolCallingChatOptions) received.get().getOptions()).getToolCallbacks())
				.extracting(candidate -> candidate.getToolDefinition().name()).doesNotContain(ThreadDocumentTool.NAME, ThreadDocumentTool.REVISE_NAME);
	}

	/**
	* 모델이 도구를 부르면 Spring AI 도구 루프(ToolCallingAdvisor) 안에서 이 턴의 공통 지점을 지나 실행되고(이슈 #390),
	* 결과를 받은 모델의 다음 답이 그대로 스트림으로 나온다. 발화자 시간대(toolContext)도 공통 지점을 지나 도구까지 간다 —
	* 도구 기본 시간대(서울)와 다른 값으로 요청해, 컨텍스트가 빠지면 시각이 달라지게 한다.
	*/
	@Test
	void toolCallsGoThroughTheTurnExecutionPointInsideTheLoop() {
		List<String> events = new CopyOnWriteArrayList<>();
		TurnToolExecutions toolExecutions = new TurnToolExecutions(UUID.randomUUID(), "trace",
				List.of(new ToolExecutionListener() {
					@Override
					public void started(String toolName, String input) {
						events.add("started:" + toolName);
					}

					@Override
					public void finished(ToolExecution execution) {
						events.add("finished:" + execution.toolName());
					}
				}));
		CurrentTimeTool tool = new CurrentTimeTool(Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneOffset.UTC),
				ZoneId.of("Asia/Seoul"));
		List<Prompt> prompts = new CopyOnWriteArrayList<>();
		LlmChatStreamService service = new LlmChatStreamService(
				ChatClient.builder(toolCallingModel("currentDateTime", "{}", prompts)), tool, mock(WebTools.class));

		List<String> answer = service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model",
				List.of(new ChatMessage("user", "지금 몇 시야")), "America/New_York", null, toolExecutions)).collectList().block();

		assertThat(String.join("", answer)).isEqualTo("답");
		assertThat(events).containsExactly("started:currentDateTime", "finished:currentDateTime");
		assertThat(toolExecutions.executions()).singleElement().satisfies(execution -> {
			assertThat(execution.succeeded()).isTrue();
			assertThat(execution.input()).isEqualTo("{}");
			assertThat(execution.outputLength()).isPositive();
		});
		// 도구가 발화자 시간대로 계산한 결과가 다음 호출에 도구 응답으로 실린다.
		assertThat(toolResponse(prompts.get(1))).contains("2026-10-07T02:00");
	}

	/** 도구가 실패해도 지금처럼 오류 문장이 모델에 돌아가 답이 이어지고, 공통 지점에는 실패로 기록된다(이슈 #390). */
	@Test
	void failingToolIsRecordedAndTheLoopContinuesAsBefore() {
		TurnToolExecutions toolExecutions = new TurnToolExecutions(UUID.randomUUID(), "trace", List.of());
		List<Prompt> prompts = new CopyOnWriteArrayList<>();
		LlmChatStreamService service = new LlmChatStreamService(
				ChatClient.builder(toolCallingModel(ThreadDocumentTool.NAME, "{\"query\":\"연차\"}", prompts)),
				new CurrentTimeTool("Asia/Seoul"), mock(WebTools.class));

		List<String> answer = service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model",
				List.of(new ChatMessage("user", "연차 규정")), null, new ThreadDocumentTool(query -> {
					throw new IllegalStateException("검색 장애");
				}, (fileName, edits) -> "등록함"), toolExecutions)).collectList().block();

		assertThat(String.join("", answer)).isEqualTo("답");
		assertThat(toolExecutions.executions()).singleElement().satisfies(execution -> {
			assertThat(execution.toolName()).isEqualTo(ThreadDocumentTool.NAME);
			assertThat(execution.succeeded()).isFalse();
			assertThat(execution.outputLength()).isNull();
			// 실패는 예외 종류 이름만 남는다 — 예외 메시지(사내 내용이 섞일 수 있음)는 기록에 넣지 않는다.
			assertThat(execution.failure()).isEqualTo("ToolExecutionException");
			assertThat(execution.elapsed().isNegative()).isFalse();
		});
		// 오류 문장은 지금처럼 Spring AI가 도구 응답으로 바꿔 모델에 돌려준다 — 공통 지점이 예외를 삼키거나 바꾸지 않는다.
		assertThat(prompts).hasSize(2);
		assertThat(toolResponse(prompts.get(1))).contains("검색 장애");
	}

	/** 도구 결과를 받은 모델 호출의 마지막 메시지(도구 응답 하나)의 내용. */
	private static String toolResponse(Prompt prompt) {
		assertThat(prompt.getInstructions()).last().isInstanceOf(ToolResponseMessage.class);
		List<Message> instructions = prompt.getInstructions();
		ToolResponseMessage message = (ToolResponseMessage) instructions.get(instructions.size() - 1);
		assertThat(message.getResponses()).hasSize(1);
		return message.getResponses().get(0).responseData();
	}

	/** 첫 호출에는 도구 호출을, 도구 결과를 받은 다음 호출에는 "답"을 돌려주는 모델. */
	private static ChatModel toolCallingModel(String toolName, String arguments) {
		return toolCallingModel(toolName, arguments, new CopyOnWriteArrayList<>());
	}

	/** 위와 같고, 받은 Prompt를 차례로 prompts에 담는다. */
	private static ChatModel toolCallingModel(String toolName, String arguments, List<Prompt> prompts) {
		return new ChatModel() {

			private final AtomicInteger calls = new AtomicInteger();

			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				prompts.add(prompt);
				AssistantMessage message = calls.getAndIncrement() == 0
						? AssistantMessage.builder().content("")
								.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", toolName, arguments)))
								.build()
						: new AssistantMessage("답");
				return new ChatResponse(List.of(new Generation(message)));
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.just(call(prompt));
			}
		};
	}
}
