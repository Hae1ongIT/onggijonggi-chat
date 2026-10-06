package com.onggijonggi.api.rag;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : QueryEmbedder.java
 * Description : 검색 문장을 문서와 같은 OpenAI 호환 임베딩 엔드포인트(사내 bge-m3)로 벡터로 바꾼다. 응답 계약 검사(모델·개수·순번·
 *               차원·유한값)는 ETL EmbeddingClient와 같은 규칙이다. ETL은 일시·영구 실패를 재시도 정책으로 나누지만 검색은 모두
 *               UNAVAILABLE이라 분류가 달라 따로 둔다. 블로킹 호출이라 검색 전용 스케줄러(rag-search)에서 실행한다.
 */
@Component
public class QueryEmbedder {

	private final RagProperties.Embedding settings;
	private final WebClient client;
	private final ObjectMapper json;

	public QueryEmbedder(RagProperties properties, WebClient.Builder builder, ObjectMapper json) {
		this.settings = properties.embedding();
		this.client = builder.clone().baseUrl(settings.url()).build();
		this.json = json;
	}

	public boolean configured() {
		return !settings.url().isBlank();
	}

	public String model() {
		return settings.model();
	}

	public int dimensions() {
		return settings.dimensions();
	}

	public float[] embed(String text) {
		if (!configured()) throw new RagUnavailableException("임베딩 주소(app.rag.embedding.url)가 비어 있다");
		String body;
		try {
			body = client.post().uri("/v1/embeddings").contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("model", settings.model(), "input", List.of(text)))
					.retrieve().bodyToMono(String.class).block(settings.timeout());
		} catch (RuntimeException error) {
			throw new RagUnavailableException("임베딩 호출 실패: " + error.getClass().getSimpleName(), error);
		}
		return vector(body);
	}

	private float[] vector(String body) {
		JsonNode response;
		try {
			response = json.readTree(body == null ? "" : body);
		} catch (RuntimeException unreadable) {
			throw new RagUnavailableException("임베딩 응답을 해석할 수 없다", unreadable);
		}
		String model = response.path("model").asString("");
		if (!model.isEmpty() && !model.equals(settings.model()))
			throw new RagUnavailableException("임베딩 모델이 다르다: 요청 " + settings.model() + ", 응답 " + model);
		JsonNode data = response.path("data");
		if (!data.isArray() || data.size() != 1 || data.get(0).path("index").asInt(-1) != 0)
			throw new RagUnavailableException("임베딩 응답 개수·순번이 요청과 다르다");
		JsonNode values = data.get(0).path("embedding");
		if (!values.isArray() || values.size() != settings.dimensions())
			throw new RagUnavailableException("임베딩 차원이 설정(" + settings.dimensions() + ")과 다르다: " + values.size());
		float[] vector = new float[values.size()];
		for (int i = 0; i < vector.length; i++) {
			JsonNode value = values.get(i);
			if (!value.isNumber() || !Double.isFinite(value.asDouble()))
				throw new RagUnavailableException("임베딩 벡터에 숫자가 아닌 값이 있다");
			vector[i] = (float) value.asDouble();
			// double로는 유한해도 float 범위를 넘으면 무한대가 된다(ETL EmbeddingClient와 같은 검사).
			if (!Float.isFinite(vector[i])) throw new RagUnavailableException("임베딩 벡터 값이 float 범위를 넘는다");
		}
		return vector;
	}
}
