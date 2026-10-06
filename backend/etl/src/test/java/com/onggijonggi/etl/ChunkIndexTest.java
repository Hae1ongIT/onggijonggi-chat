package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkIndexTest.java
 * Description : Elasticsearch 응답별 분류를 가짜 ES로 확인한다. 실제 ES와의 적재·검색은 IngestionIntegrationTest가 본다.
 *               bulk 항목 오류(400 영구, 404 별칭 없음·429 일시), 인덱스 생성 400(이미 있음만 별칭으로), 삭제의 부분 실패.
 */
class ChunkIndexTest {

	private StubHttpServer es;
	private ChunkIndex index;
	private final RunStore.Job job = new RunStore.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			1, 1, "a.txt", "a".repeat(64), null);
	private final List<Chunker.Chunk> chunks = List.of(new Chunker.Chunk("c:1:1", 1, "본문", "para=1"));
	private final List<float[]> vectors = List.of(new float[] {1, 0, 0});

	@BeforeEach
	void setUp() throws Exception {
		es = new StubHttpServer().reply("/_alias/", 200, "{}");
		var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(es.url(), "thr_doc_chunk", "thr_doc_chunk_v1", 200),
				null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		index = new ChunkIndex(properties, JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		es.close();
	}

	private static String bulkItemError(int status) {
		return "{\"errors\":true,\"items\":[{\"index\":{\"status\":" + status + ",\"error\":{\"reason\":\"r\"}}}]}";
	}

	@Test
	void bulkItemErrorsAreClassifiedAndAMissingAliasIsRecreatedNextTime() {
		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(400));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isTrue());

		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(429));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(404));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		int aliasChecks = (int) es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count();
		es.reply("/thr_doc_chunk/_bulk", 200, "{\"errors\":false,\"items\":[]}");
		index.write(job, chunks, vectors, "bge-m3");
		assertThat(es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count())
				.as("별칭이 없다는 응답 뒤에는 다시 확인한다").isEqualTo(aliasChecks + 1);
		assertThat(es.requests).anySatisfy(request -> assertThat(request.path()).contains("require_alias=true"));
	}

	@Test
	void indexCreationRejectionIsPermanentUnlessTheIndexAlreadyExists() {
		es.reply("/_alias/", 404, "{}").reply("/thr_doc_chunk_v1", 400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"nori_tokenizer not found\"}}");
		assertThatThrownBy(() -> index.ensure())
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("INDEX_REJECTED");
					assertThat(failure.getMessage()).contains("nori_tokenizer");
				});

		es.reply("/thr_doc_chunk_v1", 400, "{\"error\":{\"type\":\"resource_already_exists_exception\"}}")
				.reply("/thr_doc_chunk_v1/_alias/", 200, "{}");
		index.ensure();
		assertThat(es.requests).anySatisfy(request -> assertThat(request.path()).isEqualTo("/thr_doc_chunk_v1/_alias/thr_doc_chunk"));
	}

	@Test
	void partialOrTimedOutDeletionIsNotTreatedAsDone() {
		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":false,\"failures\":[{\"cause\":\"shard\"}]}");
		assertThatThrownBy(() -> index.delete(job.document(), 1))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":true,\"failures\":[]}");
		assertThatThrownBy(() -> index.delete(job.document(), 1)).isInstanceOf(EtlFailure.class);

		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":false,\"failures\":[],\"deleted\":3}");
		index.delete(job.document(), 1);
		es.reply("/thr_doc_chunk/_delete_by_query", 404, "{}");
		index.delete(job.document(), 1);
	}
}
