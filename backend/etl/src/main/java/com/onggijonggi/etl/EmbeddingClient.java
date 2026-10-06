package com.onggijonggi.etl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : EmbeddingClient.java
 * Description : OpenAI 호환 임베딩 엔드포인트(/v1/embeddings, 사내 bge-m3)를 배치로 부른다. 응답의 벡터 수·차원·모델이
 *               요청과 다르면 일부만 적재하지 않고 영구 실패로 끝낸다(지도 선택 11). 입력이 모델 한도를 넘는 400도 영구 실패다.
 *               Spring AI 임베딩 자동구성 대신 직접 부르는 것은 이 오류들을 일시 장애와 구분하기 위해서다.
 */
@Component
public class EmbeddingClient {

	private final RestClient client;
	private final ObjectMapper json;
	private final EtlProperties.Embedding settings;

	public EmbeddingClient(EtlProperties properties, ObjectMapper json) {
		this.settings = properties.embedding();
		this.client = HttpCalls.client(settings.url(), properties.requestTimeout());
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

	/** texts 순서대로 벡터를 돌려준다. batchSize씩 나눠 부르고, 배치마다 between을 실행한다(선점 시한 연장·삭제 확인). */
	public List<float[]> embed(List<String> texts, Runnable between) {
		List<float[]> vectors = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += settings.batchSize()) {
			List<String> batch = texts.subList(from, Math.min(texts.size(), from + settings.batchSize()));
			vectors.addAll(call(batch));
			between.run();
		}
		return vectors;
	}

	private List<float[]> call(List<String> batch) {
		String body;
		try {
			body = client.post().uri("/v1/embeddings").contentType(MediaType.APPLICATION_JSON)
					.body(json.writeValueAsString(Map.of("model", settings.model(), "input", batch)).getBytes(StandardCharsets.UTF_8))
					.retrieve().body(String.class);
		} catch (HttpClientErrorException.BadRequest tooLong) {
			throw EtlFailure.permanent("EMBEDDING_INPUT", HttpCalls.abbreviate(tooLong.getResponseBodyAsString()), tooLong);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("EMBEDDING", error);
		}
		JsonNode root = json.readTree(body == null ? "{}" : body);
		JsonNode data = root.path("data");
		if (!settings.model().equals(root.path("model").asString(settings.model())) || data.size() != batch.size())
			throw EtlFailure.permanent("EMBEDDING_CONTRACT", "응답 모델·개수 불일치: " + root.path("model").asString("") + " " + data.size() + "/" + batch.size());
		float[][] ordered = new float[batch.size()][];
		for (JsonNode item : data) {
			int index = item.path("index").asInt(-1);
			JsonNode embedding = item.path("embedding");
			if (index < 0 || index >= batch.size() || embedding.size() != settings.dimensions())
				throw EtlFailure.permanent("EMBEDDING_CONTRACT", "응답 순번·차원 불일치: index=" + index + " dim=" + embedding.size());
			float[] vector = new float[embedding.size()];
			for (int i = 0; i < vector.length; i++) vector[i] = (float) embedding.get(i).asDouble();
			ordered[index] = vector;
		}
		for (float[] vector : ordered) if (vector == null) throw EtlFailure.permanent("EMBEDDING_CONTRACT", "응답 순번 누락");
		return List.of(ordered);
	}
}
