package com.onggijonggi.api.rag;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ChunkSearcher.java
 * Description : 범위 안의 청크를 키워드(nori)와 벡터(kNN)로 따로 찾고, 채널마다 관련성 기준을 건 뒤 순위 기반(RRF)으로 합친다.
 *               두 점수는 척도가 달라 하나의 기준을 걸지 않는다(지도 선택 12). 한쪽 채널만 통과한 청크도 채택한다 — 사내 고유명사는
 *               키워드에서만 잡히고 벡터 유사도가 낮을 수 있다. 범위(고객사·방·문서별 현재 회차)는 두 채널에 같은 filter로 걸어,
 *               후보를 고른 뒤 거르는 것이 아니라 거른 범위 안에서 찾는다. 호출부는 boundedElastic에서 실행한다.
 */
@Component
public class ChunkSearcher {

	/** 검색 결과 청크 하나. 점수는 그 채널에서 기준을 통과했을 때만 있다(벡터는 코사인 유사도, 키워드는 BM25 — 순위에만 쓴다). */
	public record Hit(String document, int runSeq, int seq, String loc, String content, Double vectorScore, Double keywordScore) { }

	private static final List<String> SOURCE = List.of("doc_id", "run_seq", "seq", "loc", "content");

	private final RagProperties.Elasticsearch settings;
	private final RagProperties.Search search;
	private final WebClient client;
	private final ObjectMapper json;

	public ChunkSearcher(RagProperties properties, WebClient.Builder builder, ObjectMapper json) {
		this.settings = properties.elasticsearch();
		this.search = properties.search();
		// 기본 응답 버퍼(256KB)는 candidates를 늘리면 넘는다(청크 본문 최대 약 1300자 × 후보 수). 넘으면 정상 검색이 장애가 된다.
		this.client = builder.clone().baseUrl(settings.url()).codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
		this.json = json;
	}

	public List<Hit> search(ThreadDocumentScope scope, String query, float[] vector) {
		if (scope.targets().isEmpty()) return List.of();
		List<Object> filter = filter(scope);
		List<Hit> keyword = keyword(filter, query);
		List<Hit> semantic = vector(filter, vector);
		return fuse(keyword, semantic);
	}

	/** 고객사·방·(문서, 현재 회차) 쌍. 회차까지 거는 이유는 재처리 직후 이전 회차 청크가 정리 전까지 남아 있기 때문이다. */
	static List<Object> filter(ThreadDocumentScope scope) {
		// 방 문서 검색은 항상 방 조건을 건다. UNRESTRICTED·NONE이면 검색하지 않는다(D 협의 3·4).
		if (scope.threads().kind() != ThreadScopeFilter.Kind.THREADS)
			throw new IllegalArgumentException("방 문서 검색은 방 범위가 필요하다: " + scope.threads().kind());
		List<Object> documents = scope.targets().stream().<Object>map(target -> Map.of("bool", Map.of("filter", List.of(
				Map.of("term", Map.of("doc_id", target.document().toString())),
				Map.of("term", Map.of("run_seq", target.runSeq())))))).toList();
		return List.of(
				Map.of("term", Map.of("tnn_id", scope.tenant().toString())),
				Map.of("terms", Map.of("thr_id", scope.threads().threadIds().stream().map(Object::toString).toList())),
				Map.of("bool", Map.of("should", documents, "minimum_should_match", 1)));
	}

	private List<Hit> keyword(List<Object> filter, String query) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", search.candidates());
		body.put("_source", SOURCE);
		body.put("query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of("content", Map.of("query", query, "minimum_should_match", search.keywordMinimumShouldMatch())))), "filter", filter)));
		List<Hit> hits = new ArrayList<>();
		for (JsonNode hit : call(body)) hits.add(hit(hit, null, hit.path("_score").asDouble()));
		return hits;
	}

	private List<Hit> vector(List<Object> filter, float[] vector) {
		Map<String, Object> knn = new LinkedHashMap<>();
		knn.put("field", "emb");
		knn.put("query_vector", vector);
		knn.put("k", search.candidates());
		knn.put("num_candidates", Math.max(search.numCandidates(), search.candidates()));
		// cosine 필드에서 similarity는 원래의 코사인 유사도 기준이다(_score는 (1+cos)/2).
		knn.put("similarity", search.vectorMinSimilarity());
		knn.put("filter", filter);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", search.candidates());
		body.put("_source", SOURCE);
		body.put("knn", knn);
		List<Hit> hits = new ArrayList<>();
		// 부동소수점 오차로 1을 살짝 넘을 수 있어 [-1, 1]로 자른다.
		for (JsonNode hit : call(body)) hits.add(hit(hit, Math.max(-1, Math.min(1, 2 * hit.path("_score").asDouble() - 1)), null));
		return hits;
	}

	/** 두 채널의 순위를 RRF로 합친다. 같은 청크는 점수를 모으고, 문서당 상한을 지키며 topK개를 고른다. */
	List<Hit> fuse(List<Hit> keyword, List<Hit> semantic) {
		Map<String, Hit> merged = new LinkedHashMap<>();
		Map<String, Double> scores = new HashMap<>();
		for (List<Hit> channel : List.of(semantic, keyword)) {
			for (int rank = 0; rank < channel.size(); rank++) {
				Hit hit = channel.get(rank);
				String key = hit.document() + ":" + hit.runSeq() + ":" + hit.seq();
				merged.merge(key, hit, (a, b) -> new Hit(a.document(), a.runSeq(), a.seq(), a.loc(), a.content(),
						a.vectorScore() != null ? a.vectorScore() : b.vectorScore(),
						a.keywordScore() != null ? a.keywordScore() : b.keywordScore()));
				scores.merge(key, 1.0 / (search.rrfK() + rank + 1), Double::sum);
			}
		}
		Map<String, Integer> perDocument = new HashMap<>();
		List<Hit> result = new ArrayList<>();
		// 동점이면 먼저 들어온 쪽(벡터 채널, 그다음 키워드 채널의 순위 순)이 앞선다 — 안정 정렬이라 결정적이다.
		merged.entrySet().stream().sorted(Comparator.comparing((Map.Entry<String, Hit> entry) -> scores.get(entry.getKey())).reversed())
				.forEach(entry -> {
					if (result.size() >= search.topK()) return;
					if (perDocument.merge(entry.getValue().document(), 1, Integer::sum) > search.perDocument()) return;
					result.add(entry.getValue());
				});
		return result;
	}

	private Iterable<JsonNode> call(Map<String, Object> body) {
		String response;
		try {
			response = client.post().uri("/{alias}/_search", settings.alias()).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(body).retrieve().bodyToMono(String.class).block(settings.timeout());
		} catch (RuntimeException error) {
			// 별칭이 없는 404도 장애다 — 검색 대상 문서가 있는데 색인이 없다(인덱스 삭제·ES 볼륨 초기화).
			throw new RagUnavailableException("Elasticsearch 검색 실패: " + error.getClass().getSimpleName(), error);
		}
		try {
			JsonNode tree = json.readTree(response == null ? "" : response);
			if (tree.path("timed_out").asBoolean(false) || tree.path("_shards").path("failed").asInt(0) > 0)
				throw new RagUnavailableException("Elasticsearch 검색이 일부 샤드에서 끝나지 않았다");
			// 결과 목록이 없는 응답(빈 본문, 프록시 오류 페이지를 JSON으로 감싼 것 등)을 "결과 0건"으로 읽으면 장애가 근거 없음으로 숨는다.
			JsonNode hits = tree.path("hits").path("hits");
			if (!hits.isArray()) throw new RagUnavailableException("Elasticsearch 응답에 검색 결과 목록이 없다");
			return hits;
		} catch (RagUnavailableException error) {
			throw error;
		} catch (RuntimeException unreadable) {
			throw new RagUnavailableException("Elasticsearch 응답을 해석할 수 없다", unreadable);
		}
	}

	private static Hit hit(JsonNode hit, Double vectorScore, Double keywordScore) {
		JsonNode source = hit.path("_source");
		return new Hit(source.path("doc_id").asString(""), source.path("run_seq").asInt(), source.path("seq").asInt(),
				source.path("loc").asString(""), source.path("content").asString(""), vectorScore, keywordScore);
	}
}
