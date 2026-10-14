package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : QueryEmbedderTest.java
 * Description : 질문 임베딩의 응답 계약. 모델·개수·순번·차원·값이 하나라도 어긋나거나 호출이 실패하면 검색 장애(UNAVAILABLE)로
 *               끝나는지, 주소가 없으면 부르지 않는지 확인한다.
 */
class QueryEmbedderTest {

	private StubHttpServer server;

	@BeforeEach
	void setUp() throws Exception {
		server = new StubHttpServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private QueryEmbedder embedder(String url) {
		var properties = new RagProperties(null, new RagProperties.Embedding(url, "bge-m3", 3, Duration.ofSeconds(2)), null, null, true);
		return new QueryEmbedder(properties, WebClient.builder(), JsonMapper.builder().build());
	}

	private static String response(String model, String data) {
		return "{\"model\":\"" + model + "\",\"data\":" + data + "}";
	}

	@Test
	void aSingleVectorIsReturnedAndTheRequestNamesTheModel() {
		server.reply("/v1/embeddings", 200, response("bge-m3", "[{\"index\":0,\"embedding\":[0.5,0,1]}]"));

		assertThat(embedder(server.url()).embed("연차 이월")).containsExactly(0.5f, 0f, 1f);
		assertThat(server.requests.get(0).body()).contains("\"model\":\"bge-m3\"").contains("연차 이월");
	}

	@ParameterizedTest
	@ValueSource(strings = {"model", "count", "index", "dimension", "nonNumber", "floatOverflow", "notJson", "serverError"})
	void anyContractViolationOrFailureIsUnavailable(String violation) {
		switch (violation) {
			case "model" -> server.reply("/v1/embeddings", 200, response("other", "[{\"index\":0,\"embedding\":[1,0,0]}]"));
			case "count" -> server.reply("/v1/embeddings", 200, response("bge-m3", "[]"));
			case "index" -> server.reply("/v1/embeddings", 200, response("bge-m3", "[{\"index\":3,\"embedding\":[1,0,0]}]"));
			case "dimension" -> server.reply("/v1/embeddings", 200, response("bge-m3", "[{\"index\":0,\"embedding\":[1,0]}]"));
			case "nonNumber" -> server.reply("/v1/embeddings", 200, response("bge-m3", "[{\"index\":0,\"embedding\":[\"NaN\",0,0]}]"));
			case "floatOverflow" -> server.reply("/v1/embeddings", 200, response("bge-m3", "[{\"index\":0,\"embedding\":[1e39,0,0]}]"));
			case "notJson" -> server.reply("/v1/embeddings", 200, "<html>gateway</html>");
			default -> server.reply("/v1/embeddings", 503, "{}");
		}
		assertThatThrownBy(() -> embedder(server.url()).embed("질문")).isInstanceOf(RagUnavailableException.class);
	}

	@Test
	void aMissingEndpointIsNotCalled() {
		QueryEmbedder unconfigured = embedder("");
		assertThat(unconfigured.configured()).isFalse();
		assertThatThrownBy(() -> unconfigured.embed("질문")).isInstanceOf(RagUnavailableException.class);
		assertThat(server.requests).isEmpty();
	}
}
