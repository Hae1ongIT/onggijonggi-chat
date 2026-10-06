package com.onggijonggi.api.rag;

import com.onggijonggi.api.chat.ChatMessage;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Class Name : QueryRewriter.java
 * Description : 후속 질문("그럼 그거 넘기면?")을 최근 대화를 보고 독립된 검색 문장으로 다시 쓴다. 채팅은 최근 대화를 AI에 넘겨 맥락을
 *               잇는데 검색만 마지막 메시지로 하면 AI는 맥락을 아는데 문서를 못 찾는 어긋남이 생긴다(#344 결정 3). 앞 대화가 없으면
 *               부르지 않는다. 실패·시간 초과·이상한 결과면 질문 그대로 검색한다 — 다시 쓰기 실패를 검색 장애로 만들지 않는다.
 *               로그에는 질문·대화 본문을 남기지 않는다.
 *               게이트웨이는 채팅과 같지만 클라이언트는 따로 만든다 — 시간 제한(기본 5초)을 HTTP 수준에 걸고 재시도하지 않아, 늦은 응답을
 *               기다리던 스레드가 시간 제한 뒤에도 남지 않게 한다(공용 ChatModel의 시간 제한은 클라이언트 전체 설정이라 요청마다 줄일 수 없다).
 *               빈으로 등록하지 않는다 — ChatModel이 둘이 되면 채팅의 ChatClient.Builder 자동 구성이 하나를 고르지 못한다.
 */
@Component
public class QueryRewriter {

	private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
	/** 다시 쓴 문장이 이보다 길면 답을 지어냈거나 지시를 따르지 않은 것으로 보고 버린다. */
	static final int MAX_LENGTH = 300;
	/**
	 * 출력 토큰 상한. 지시를 어기고 길게 답해도 비용이 늘지 않게 한다. 추론(thinking) 토큰까지 한도에 넣는 모델이 있어 검색 문장 한 줄보다
	 * 넉넉히 둔다 — 한도에서 잘린 응답은 버린다.
	 */
	static final int MAX_TOKENS = 512;
	/** 대화 기록의 메시지 하나를 보여 줄 최대 글자 수. */
	private static final int MESSAGE_LIMIT = 1000;
	/** 목록 표시("1. ", "2) ", "- ", "• "). */
	private static final Pattern LIST_MARK = Pattern.compile("^(?:\\d{1,2}[.)]|[-•])\\s+");
	/** 모델이 붙이기 쉬운 앞머리 라벨("검색 질문:", "검색어:", "질문:", "Query:"). */
	private static final Pattern LABEL = Pattern.compile(
			"^(?:검색\\s*(?:질문|문장|쿼리)|검색어|질문|쿼리|search\\s*query|query)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
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

	@Autowired
	public QueryRewriter(RagProperties properties, @Value("${spring.ai.openai.base-url}") String gatewayUrl,
			@Value("${spring.ai.openai.api-key:}") String gatewayKey,
			@Value("${app.thread.ai.model:${spring.ai.openai.chat.options.model}}") String defaultModel) {
		this(ChatClient.create(OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(gatewayUrl)
				.apiKey(gatewayKey.isBlank() ? "none" : gatewayKey).timeout(properties.rewrite().timeout()).maxRetries(0).build()).build()),
				properties, defaultModel);
	}

	QueryRewriter(ChatClient chatClient, RagProperties properties, String defaultModel) {
		this.chatClient = chatClient;
		this.settings = properties.rewrite();
		this.defaultModel = defaultModel;
	}

	/** history는 질문 앞의 대화(오래된 것부터)다. 질문 자체는 넣지 않는다. 블로킹 호출이라 검색 전용 스케줄러(rag-search)에서 실행한다. */
	public Result rewrite(String question, List<ChatMessage> history, String requestModel) {
		List<ChatMessage> recent = recent(history);
		if (recent.isEmpty()) return new Result(question, false);
		String model = !settings.model().isBlank() ? settings.model()
				: requestModel != null && !requestModel.isBlank() ? requestModel : defaultModel;
		// 요청의 modelId는 사용자가 정한다. 로그에 넣기 전에 허용 문자 밖을 바꿔 줄바꿈 등으로 가짜 로그 줄을 만들지 못하게 한다.
		String logged = loggable(model);
		long started = System.nanoTime();
		try {
			ChatResponse response = chatClient.prompt()
					.system(SYSTEM_PROMPT)
					.user(prompt(recent, question))
					.options(ChatOptions.builder().model(model).temperature(0.0).maxTokens(MAX_TOKENS))
					.call()
					.chatResponse();
			Generation generation = response == null ? null : response.getResult();
			// 출력 한도에서 잘렸으면(추론 토큰이 한도를 먹는 모델 등) 문장 중간일 수 있어 버린다.
			String finish = generation == null ? null : generation.getMetadata().getFinishReason();
			String query = finish != null && finish.equalsIgnoreCase("length") ? null
					: clean(generation == null ? null : generation.getOutput().getText());
			if (query == null) {
				log.warn("검색 문장 다시 쓰기 결과를 버렸다(비었거나 {}자 초과이거나 출력 한도에서 잘림) — 질문 그대로 검색한다: model={}", MAX_LENGTH, logged);
				return new Result(question, false);
			}
			log.debug("검색 문장을 다시 썼다: model={} {}ms", logged, Duration.ofNanos(System.nanoTime() - started).toMillis());
			return new Result(query, !query.equals(question.strip()));
		} catch (RuntimeException error) {
			log.warn("검색 문장 다시 쓰기 실패 — 질문 그대로 검색한다: model={} {}ms {}", logged,
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

	/** 로그용 모델 이름. 허용 문자(영숫자·_·.·:·/·@·-) 밖은 ?로 바꾼다. */
	static String loggable(String model) {
		return model.replaceAll("[^\\w.:/@-]", "?");
	}

	/** 긴 AI 답변이 프롬프트를 키우지 않게 메시지마다 앞부분만 보여 준다. 가리키는 대상은 보통 앞부분에 있다. */
	private static String abbreviate(String content) {
		return content.length() <= MESSAGE_LIMIT ? content : content.substring(0, MESSAGE_LIMIT) + "…";
	}

	/** 한 줄로 정리한다. 코드 펜스 줄은 건너뛰고 목록 표시·앞머리 라벨·감싼 따옴표·백틱·굵게(**)를 뗀다. 비었거나 너무 길면 null. */
	static String clean(String output) {
		if (output == null) return null;
		String line = output.strip().lines().map(String::strip)
				.filter(value -> !value.isEmpty() && !value.startsWith("```")).findFirst().orElse("");
		// 굵게(**)는 어디에 있든 지운다 — 라벨을 감싼 경우(**검색어:**)도 있어 라벨보다 먼저 지운다.
		line = LIST_MARK.matcher(line.replace("**", "")).replaceFirst("");
		line = LABEL.matcher(line).replaceFirst("");
		line = line.replaceAll("^[\"'“”‘’`*]+|[\"'“”‘’`*]+$", "").strip();
		return line.isEmpty() || line.length() > MAX_LENGTH ? null : line;
	}
}
