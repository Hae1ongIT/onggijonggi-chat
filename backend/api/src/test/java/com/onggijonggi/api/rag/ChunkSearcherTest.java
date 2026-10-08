package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkSearcherTest.java
 * Description : 범위 조건의 모양(방 범위 없이는 검색하지 않음)과 두 채널의 순위 결합(RRF)·문서당 상한·점수 보존을 확인한다.
 *               태그 채널(#362)은 대표 조각 선택(벡터 결과 재사용 → 문서 안 kNN → 최소 유사도 미달 제외), 장애 시 두 채널 유지, 꺼짐이면 요청 없음을 본다.
 *               실제 Elasticsearch에서의 범위 격리는 ThreadDocumentSearchIntegrationTest가 본다.
 */
class ChunkSearcherTest {

	private final ChunkSearcher searcher = new ChunkSearcher(new RagProperties(
			new RagProperties.Elasticsearch("http://localhost:1", "thr_doc_chunk", Duration.ofSeconds(1)), null, null,
			new RagProperties.Search(3, 2, 20, 100, 0.5, "60%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());

	private static ChunkSearcher.Hit hit(String doc, int seq, Double vector, Double keyword) {
		return new ChunkSearcher.Hit(doc + ":1:" + seq, doc, 1, seq, "para=" + seq, "본문" + seq, vector, keyword);
	}

	@Test
	void aChunkFoundByBothChannelsRanksFirstAndKeepsBothScores() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("a", 2, 0.8, null));
		var keyword = List.of(hit("b", 1, null, 7.0), hit("a", 2, null, 5.0));

		var fused = searcher.fuse(keyword, semantic);

		assertThat(fused.get(0).seq()).isEqualTo(2);
		assertThat(fused.get(0).document()).isEqualTo("a");
		assertThat(fused.get(0).vectorScore()).isEqualTo(0.8);
		assertThat(fused.get(0).keywordScore()).isEqualTo(5.0);
	}

	@Test
	void aKeywordOnlyHitIsKeptAndEachDocumentIsCapped() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("a", 2, 0.8, null), hit("a", 3, 0.7, null));
		var keyword = List.of(hit("b", 1, null, 7.0));

		var fused = searcher.fuse(keyword, semantic);

		assertThat(fused).hasSize(3);
		assertThat(fused).filteredOn(hit -> hit.document().equals("a")).hasSize(2);
		assertThat(fused).anySatisfy(hit -> assertThat(hit.document()).isEqualTo("b"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"timed_out\":true,\"hits\":{\"hits\":[]}}", "{\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}",
			"{\"took\":1}", "", "<html>proxy</html>"})
	void anIncompleteOrMalformedResponseIsAnOutageNotAnEmptyResult(String body) throws Exception {
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, body)) {
			var stubbed = new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)),
					null, null, new RagProperties.Search(3, 2, 20, 100, 0.5, "75%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());
			var scope = new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
					List.of(new ThreadDocumentScope.Target(UUID.randomUUID(), "a.txt", 1, "bge-m3", 3)));

			assertThatThrownBy(() -> stubbed.search(scope, "연차", new float[] {1, 0, 0})).isInstanceOf(RagUnavailableException.class);
		}
	}

	@Test
	void theFilterBindsTenantRoomAndEachDocumentToItsCurrentRunAndRefusesAnUnscopedSearch() {
		UUID tenant = UUID.randomUUID(), room = UUID.randomUUID(), doc = UUID.randomUUID();
		var scope = new ThreadDocumentScope(tenant, ThreadScopeFilter.of(List.of(room)),
				List.of(new ThreadDocumentScope.Target(doc, "a.txt", 2, "bge-m3", 1024)));

		String filter = JsonMapper.builder().build().writeValueAsString(ChunkSearcher.filter(scope));

		assertThat(filter).contains("\"tnn_id\":\"" + tenant + "\"").contains("\"thr_id\":[\"" + room + "\"]")
				.contains("\"doc_id\":\"" + doc + "\"").contains("\"run_seq\":2").contains("\"minimum_should_match\":1");
		for (ThreadScopeFilter unscoped : List.of(ThreadScopeFilter.unrestricted(), ThreadScopeFilter.of(List.of())))
			assertThatThrownBy(() -> ChunkSearcher.filter(new ThreadDocumentScope(tenant, unscoped, scope.targets())))
					.isInstanceOf(IllegalArgumentException.class);
	}

	/** 벡터 점수는 _score(=(1+cos)/2)를 코사인 유사도로 바꾸고 [-1, 1]로 자른다. 두 채널 요청에 각 기준이 실린다. */
	@Test
	void scoresAreConvertedAndClampedAndEachChannelCarriesItsCriterion() throws Exception {
		String hits = "{\"hits\":{\"hits\":[{\"_score\":%s,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"%s\",\"run_seq\":1,\"seq\":%d,\"loc\":\"para=1\",\"content\":\"본문\"}}]}}";
		UUID doc = UUID.randomUUID();
		var scope = new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
				List.of(new ThreadDocumentScope.Target(doc, "a.txt", 1, "bge-m3", 3)));
		for (var expected : List.of(List.of("0.75", 0.5), List.of("1.0000001", 1.0))) {
			try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, String.format(hits, expected.get(0), doc, 1))) {
				var stubbed = new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)),
						null, null, new RagProperties.Search(3, 2, 20, 100, 0.5, "75%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());

				var result = stubbed.search(scope, "연차", new float[] {1, 0, 0});

				assertThat(result).singleElement().satisfies(hit -> assertThat(hit.vectorScore()).isEqualTo((Double) expected.get(1)));
				assertThat(es.requests).anySatisfy(request -> assertThat(request.body()).contains("\"minimum_should_match\":\"75%\""));
				assertThat(es.requests).anySatisfy(request -> assertThat(request.body()).contains("\"similarity\":0.5"));
			}
		}
	}

	@Test
	void aTagChannelHitAddsItsRankAndATagOnlyRepresentativeIsKept() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("b", 1, 0.8, null));
		var keyword = List.of(hit("a", 1, null, 7.0));
		var tag = List.of(hit("b", 1, 0.8, null), hit("c", 4, 0.4, null));

		var fused = searcher.fuse(List.of(semantic, keyword, tag));

		assertThat(fused).extracting(ChunkSearcher.Hit::document).containsExactly("a", "b", "c");
	}

	/** a는 벡터 결과의 조각을 대표로 재사용하고, b는 문서 안 kNN 1건을, c는 최소 유사도 미달(빈 결과)이라 뺀다. */
	@Test
	void representativesReuseVectorHitsThenSearchInsideTheDocumentAndDropWeakOnes() throws Exception {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		String chunk = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"%s:1:%d\",\"doc_id\":\"%s\",\"run_seq\":1,\"seq\":%d,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		Function<String, StubHttpServer.Reply> chunks = body -> {
			if (body.contains("\"match\"")) return hits();
			if (body.contains("\"k\":1,")) return body.contains(b.toString()) ? hits(String.format(chunk, b, 7, b, 7)) : hits();
			return hits(String.format(chunk, a, 2, a, 2));
		};
		String tags = "{\"_source\":{\"doc_id\":\"%s\",\"run_seq\":1}}";
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", chunks)
				.reply("/thr_doc_tag/_search", 200, "{\"hits\":{\"hits\":[" + String.format(tags, c) + "," + String.format(tags, b) + ","
						+ String.format(tags, a) + "]}}")) {
			var result = tagged(es, true).search(scope(a, b, c), "연차", new float[] {1, 0, 0});

			assertThat(result).extracting(ChunkSearcher.Hit::chunkId).containsExactly(a + ":1:2", b + ":1:7");
			assertThat(es.requests).filteredOn(request -> request.body().contains("\"k\":1,")).as("a는 벡터 결과를 재사용한다").hasSize(2)
					.allSatisfy(request -> assertThat(request.body()).contains("\"similarity\":0.35").doesNotContain(a.toString()));
			assertThat(es.requests).filteredOn(request -> request.path().startsWith("/thr_doc_tag")).singleElement()
					.satisfies(request -> assertThat(request.body()).contains("kyw^2").contains("\"minimum_should_match\":\"40%\""));
		}
	}

	@Test
	void aTagChannelOutageKeepsTheOtherChannelsAndADisabledChannelSendsNothing() throws Exception {
		UUID a = UUID.randomUUID();
		String chunk = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"" + a + "\",\"run_seq\":1,\"seq\":1,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		for (boolean enabled : List.of(true, false)) {
			try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, "{\"hits\":{\"hits\":[" + chunk + "]}}")
					.reply("/thr_doc_tag/_search", 404, "{\"error\":{\"type\":\"index_not_found_exception\"}}")) {
				var result = tagged(es, enabled).search(scope(a), "연차", new float[] {1, 0, 0});

				assertThat(result).as("enabled=" + enabled).singleElement().satisfies(hit -> assertThat(hit.chunkId()).isEqualTo("c"));
				assertThat(es.requests.stream().anyMatch(request -> request.path().startsWith("/thr_doc_tag"))).isEqualTo(enabled);
			}
		}
	}

	private static StubHttpServer.Reply hits(String... hits) {
		return new StubHttpServer.Reply(200, "{\"hits\":{\"hits\":[" + String.join(",", hits) + "]}}");
	}

	private static ThreadDocumentScope scope(UUID... documents) {
		return new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
				List.of(documents).stream().map(document -> new ThreadDocumentScope.Target(document, "a.txt", 1, "bge-m3", 3)).toList());
	}

	private static ChunkSearcher tagged(StubHttpServer es, boolean enabled) {
		return new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)), null, null,
				new RagProperties.Search(5, 2, 20, 100, 0.5, "75%", 60, 2, 10), true),
				new RagTagProperties(enabled, "thr_doc_tag", "40%", 10, 0.35), WebClient.builder(), JsonMapper.builder().build());
	}
}
