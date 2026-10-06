package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : EmbeddingClientTest.java
 * Description : 임베딩 응답 계약. 개수·순번·차원·모델·값이 하나라도 어긋나면 일부만 적재하지 않고 영구 실패(EMBEDDING_CONTRACT)로
 *               끝나는지, 입력 거절(400)과 해석 불가 응답이 각각 맞게 분류되는지 확인한다(지도 선택 11).
 */
class EmbeddingClientTest {

	private StubHttpServer server;
	private EmbeddingClient client;

	@BeforeEach
	void setUp() throws Exception {
		server = new StubHttpServer();
		var properties = new EtlProperties(null, null, new EtlProperties.Embedding(server.url(), "bge-m3", 3, 32),
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		client = new EmbeddingClient(properties, JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private static String item(int index, String vector) {
		return "{\"index\":" + index + ",\"embedding\":" + vector + "}";
	}

	private static String response(String model, String... items) {
		return "{\"model\":\"" + model + "\",\"data\":[" + String.join(",", items) + "]}";
	}

	@Test
	void vectorsAreReturnedInInputOrderEvenWhenTheResponseIsShuffled() {
		server.reply("/v1/embeddings", 200, response("bge-m3", item(1, "[0,1,0]"), item(0, "[1,0,0]")));

		var vectors = client.embed(List.of("가", "나"), () -> { });

		assertThat(vectors.get(0)).containsExactly(1f, 0f, 0f);
		assertThat(vectors.get(1)).containsExactly(0f, 1f, 0f);
	}

	@ParameterizedTest
	@ValueSource(strings = {"model", "count", "index", "dimension", "nonFinite", "duplicate"})
	void anyContractViolationFailsTheWholeBatchPermanently(String violation) {
		String body = switch (violation) {
			case "model" -> response("other-model", item(0, "[1,0,0]"), item(1, "[0,1,0]"));
			case "count" -> response("bge-m3", item(0, "[1,0,0]"));
			case "index" -> response("bge-m3", item(0, "[1,0,0]"), item(5, "[0,1,0]"));
			case "dimension" -> response("bge-m3", item(0, "[1,0,0]"), item(1, "[0,1]"));
			case "nonFinite" -> response("bge-m3", item(0, "[1,0,0]"), item(1, "[\"NaN\",0,0]"));
			default -> response("bge-m3", item(0, "[1,0,0]"), item(0, "[0,1,0]"));
		};
		server.reply("/v1/embeddings", 200, body);

		assertThatThrownBy(() -> client.embed(List.of("가", "나"), () -> { }))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("EMBEDDING_CONTRACT");
					assertThat(failure.permanent()).isTrue();
				});
	}

	@Test
	void rejectedInputIsPermanentButAnUnreadableReplyIsRetried() {
		server.reply("/v1/embeddings", 400, "{\"error\":\"too long\"}");
		assertThatThrownBy(() -> client.embed(List.of("가"), () -> { }))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("EMBEDDING_INPUT");
					assertThat(failure.permanent()).isTrue();
				});

		server.reply("/v1/embeddings", 200, "<html>gateway error</html>");
		assertThatThrownBy(() -> client.embed(List.of("가"), () -> { }))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("EMBEDDING_UNAVAILABLE");
					assertThat(failure.permanent()).isFalse();
				});
	}

	@Test
	void largeInputsAreSentInBatchesWithTheCallbackBetweenThem() {
		server.reply("/v1/embeddings", 200, response("bge-m3", item(0, "[1,0,0]")));
		var properties = new EtlProperties(null, null, new EtlProperties.Embedding(server.url(), "bge-m3", 3, 1),
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		var batched = new EmbeddingClient(properties, JsonMapper.builder().build());
		int[] between = {0};

		assertThat(batched.embed(List.of("가", "나", "다"), () -> between[0]++)).hasSize(3);
		assertThat(server.requests).hasSize(3);
		assertThat(between[0]).isEqualTo(3);
	}
}
