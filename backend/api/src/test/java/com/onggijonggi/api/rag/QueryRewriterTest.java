package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.chat.ChatMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Class Name : QueryRewriterTest.java
 * Description : 검색 문장 다시 쓰기의 조건과 실패 처리. 앞 대화가 없으면 LLM을 부르지 않고, 실패·시간 초과·이상한 결과면 질문 그대로
 *               검색하며, 최근 대화만 보여 주고 온도 0·지정 모델로 부르는지 확인한다.
 */
class QueryRewriterTest {

	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	private QueryRewriter rewriter(String configuredModel, Duration timeout, Function<Prompt, String> reply) {
		ChatModel model = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				prompts.add(prompt);
				return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.apply(prompt)))));
			}
		};
		var properties = new RagProperties(null, null, new RagProperties.Rewrite(configuredModel, timeout, 2), null, true);
		return new QueryRewriter(ChatClient.builder(model), properties, "default-model");
	}

	private static final List<ChatMessage> HISTORY = List.of(
			new ChatMessage("user", "보안 지침 알려줘"),
			new ChatMessage("assistant", "외부 저장 매체는 승인 없이 연결하지 않습니다."),
			new ChatMessage("user", "연차 이월 규정 알려줘"),
			new ChatMessage("assistant", "다음 해 3월 말까지 이월할 수 있습니다."));

	@Test
	void aFirstQuestionIsSearchedAsIsWithoutCallingTheModel() {
		var result = rewriter("", Duration.ofSeconds(2), prompt -> "x").rewrite("연차 이월 규정 알려줘", List.of(), "gemma");

		assertThat(result).isEqualTo(new QueryRewriter.Result("연차 이월 규정 알려줘", false));
		assertThat(prompts).isEmpty();
	}

	@Test
	void aFollowUpIsRewrittenFromTheRecentConversationWithTheConversationModelAtTemperatureZero() {
		var result = rewriter("", Duration.ofSeconds(2), prompt -> "\"연차 이월 기한을 넘기면 어떻게 되나\"\n")
				.rewrite("그럼 그거 넘기면 어떻게 돼?", HISTORY, "gemma");

		assertThat(result).isEqualTo(new QueryRewriter.Result("연차 이월 기한을 넘기면 어떻게 되나", true));
		Prompt prompt = prompts.get(0);
		assertThat(prompt.getOptions().getModel()).isEqualTo("gemma");
		assertThat(prompt.getOptions().getTemperature()).isEqualTo(0.0);
		String user = prompt.getUserMessage().getText();
		assertThat(user).contains("연차 이월 규정 알려줘").contains("그럼 그거 넘기면 어떻게 돼?");
		assertThat(user).as("최근 대화(설정 2개)만 보여 준다").doesNotContain("보안 지침");
	}

	@Test
	void aConfiguredModelWinsAndAMissingRequestModelFallsBackToTheDefault() {
		rewriter("internal", Duration.ofSeconds(2), prompt -> "검색 문장").rewrite("그건?", HISTORY, "gemma");
		rewriter("", Duration.ofSeconds(2), prompt -> "검색 문장").rewrite("그건?", HISTORY, null);

		assertThat(prompts).extracting(prompt -> prompt.getOptions().getModel()).containsExactly("internal", "default-model");
	}

	@Test
	void failureTimeoutOrAnUnusableAnswerFallsBackToTheQuestion() {
		String question = "그럼 그거 넘기면 어떻게 돼?";
		var failing = rewriter("", Duration.ofSeconds(2), prompt -> { throw new IllegalStateException("gateway down"); });
		var slow = rewriter("", Duration.ofMillis(100), prompt -> {
			try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
			return "늦은 답";
		});
		var verbose = rewriter("", Duration.ofSeconds(2), prompt -> "답".repeat(QueryRewriter.MAX_LENGTH + 1));
		var blank = rewriter("", Duration.ofSeconds(2), prompt -> "  \n ");

		for (QueryRewriter rewriter : List.of(failing, slow, verbose, blank))
			assertThat(rewriter.rewrite(question, HISTORY, "gemma")).isEqualTo(new QueryRewriter.Result(question, false));
	}
}
