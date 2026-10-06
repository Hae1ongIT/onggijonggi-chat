package com.onggijonggi.api.rag;

import com.onggijonggi.api.chat.ChatMessage;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : QueryRewriter.java
 * Description : 후속 질문("그럼 그거 넘기면?")을 최근 대화를 보고 독립된 검색 문장으로 다시 쓴다. 채팅은 최근 대화를 AI에 넘겨 맥락을
 *               잇는데 검색만 마지막 메시지로 하면 AI는 맥락을 아는데 문서를 못 찾는 어긋남이 생긴다(#344 결정 3). 앞 대화가 없으면
 *               부르지 않는다. 실패·시간 초과·이상한 결과면 질문 그대로 검색한다 — 다시 쓰기 실패를 검색 장애로 만들지 않는다.
 *               로그에는 질문·대화 본문을 남기지 않는다.
 */
@Component
public class QueryRewriter {

	private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
	/** 다시 쓴 문장이 이보다 길면 답을 지어냈거나 지시를 따르지 않은 것으로 보고 버린다. */
	static final int MAX_LENGTH = 300;
	/** 대화 기록의 메시지 하나를 보여 줄 최대 글자 수. */
	private static final int MESSAGE_LIMIT = 1000;
	private static final String SYSTEM_PROMPT = """
			너는 문서 검색어를 만드는 도구다. 대화 기록과 마지막 질문을 보고, 마지막 질문을 대화 없이도 뜻이 통하는 검색 질문 한 문장으로 다시 쓴다.
			- "그거", "거기", "그럼" 같은 말은 대화에서 가리키는 대상으로 바꾼다.
			- 마지막 질문이 이미 독립적이면 그대로 쓴다. 주제가 바뀌었으면 앞 대화를 끌어오지 않는다.
			- 질문에 답하지 않는다. 설명·따옴표·접두어 없이 검색 질문 한 문장만 출력한다.
			""";

	/** 다시 쓰기 결과. rewritten이 false면 query는 원래 질문이다. */
	public record Result(String query, boolean rewritten) { }

	private final ChatClient chatClient;
	private final RagProperties.Rewrite settings;
	private final String defaultModel;

	public QueryRewriter(ChatClient.Builder chatClientBuilder, RagProperties properties,
			@Value("${app.thread.ai.model:${spring.ai.openai.chat.options.model}}") String defaultModel) {
		this.chatClient = chatClientBuilder.build();
		this.settings = properties.rewrite();
		this.defaultModel = defaultModel;
	}

	/** history는 질문 앞의 대화(오래된 것부터)다. 질문 자체는 넣지 않는다. 호출부는 boundedElastic에서 실행한다. */
	public Result rewrite(String question, List<ChatMessage> history, String requestModel) {
		List<ChatMessage> recent = recent(history);
		if (recent.isEmpty()) return new Result(question, false);
		String model = !settings.model().isBlank() ? settings.model()
				: requestModel != null && !requestModel.isBlank() ? requestModel : defaultModel;
		long started = System.nanoTime();
		try {
			String output = Mono.fromCallable(() -> chatClient.prompt()
							.system(SYSTEM_PROMPT)
							.user(prompt(recent, question))
							.options(ChatOptions.builder().model(model).temperature(0.0))
							.call()
							.content())
					.subscribeOn(Schedulers.boundedElastic())
					.block(settings.timeout());
			String query = clean(output);
			if (query == null) {
				log.warn("검색 문장 다시 쓰기 결과를 버렸다(비었거나 {}자 초과) — 질문 그대로 검색한다: model={}", MAX_LENGTH, model);
				return new Result(question, false);
			}
			log.debug("검색 문장을 다시 썼다: model={} {}ms", model, Duration.ofNanos(System.nanoTime() - started).toMillis());
			return new Result(query, !query.equals(question.strip()));
		} catch (RuntimeException error) {
			log.warn("검색 문장 다시 쓰기 실패 — 질문 그대로 검색한다: model={} {}ms {}", model,
					Duration.ofNanos(System.nanoTime() - started).toMillis(), error.getClass().getSimpleName());
			return new Result(question, false);
		}
	}

	private List<ChatMessage> recent(List<ChatMessage> history) {
		if (history == null || history.isEmpty() || settings.history() <= 0) return List.of();
		List<ChatMessage> usable = history.stream()
				.filter(message -> message != null && message.content() != null && !message.content().isBlank())
				.filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
				.toList();
		return usable.subList(Math.max(0, usable.size() - settings.history()), usable.size());
	}

	private static String prompt(List<ChatMessage> recent, String question) {
		String conversation = recent.stream()
				.map(message -> ("user".equals(message.role()) ? "사용자: " : "AI: ") + abbreviate(message.content().strip()))
				.collect(Collectors.joining("\n"));
		return "[대화 기록]\n" + conversation + "\n\n[마지막 질문]\n" + question.strip();
	}

	/** 긴 AI 답변이 프롬프트를 키우지 않게 메시지마다 앞부분만 보여 준다. 가리키는 대상은 보통 앞부분에 있다. */
	private static String abbreviate(String content) {
		return content.length() <= MESSAGE_LIMIT ? content : content.substring(0, MESSAGE_LIMIT) + "…";
	}

	/** 한 줄로 정리한다. 비었거나 너무 길면 null. */
	static String clean(String output) {
		if (output == null) return null;
		String line = output.strip().lines().map(String::strip).filter(value -> !value.isEmpty()).findFirst().orElse("");
		line = line.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").strip();
		return line.isEmpty() || line.length() > MAX_LENGTH ? null : line;
	}
}
