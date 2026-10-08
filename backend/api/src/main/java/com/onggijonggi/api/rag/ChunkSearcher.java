package com.onggijonggi.api.rag;

import static com.onggijonggi.common.document.ChunkIndexContract.*;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import com.onggijonggi.common.document.TagIndexContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ChunkSearcher.java
 * Description : 범위 안의 청크를 키워드(nori)와 벡터(kNN)로 따로 찾고, 채널마다 관련성 기준을 건 뒤 순위 기반(RRF)으로 합친다.
 *               태그 채널(#362, app.rag.tag.enabled)을 켜면 문서 태그(키워드·요약)로 관련 문서를 찾아 그 문서의 대표 조각을 세 번째 채널로 더한다 —
 *               질문 표현이 본문과 달라도 찾게 한다. 태그 채널이 실패하면(색인 없음·장애) 검색 실패로 만들지 않고 두 채널로 계속한다.
 *               두 점수는 척도가 달라 하나의 기준을 걸지 않는다. 한쪽 채널만 통과한 청크도 채택한다 — 사내 고유명사는
 *               키워드에서만 잡히고 벡터 유사도가 낮을 수 있다. 범위(고객사·방·문서별 현재 회차)는 두 채널에 같은 filter로 걸어,
 *               후보를 고른 뒤 거르는 것이 아니라 거른 범위 안에서 찾는다. 블로킹 호출이라 검색 전용 스케줄러(rag-search)에서 실행한다.
 */
@Component
public class ChunkSearcher {

	/** 검색 결과 청크 하나. 점수는 그 채널에서 기준을 통과했을 때만 있다(벡터는 코사인 유사도, 키워드는 BM25 — 순위에만 쓴다). */
	public record Hit(String chunkId, String document, int runSeq, int seq, String loc, String content, Double vectorScore, Double keywordScore) { }

	private static final Logger log = LoggerFactory.getLogger(ChunkSearcher.class);

	/** ES 응답 버퍼. 후보 수 × 청크 최대 길이(약 1300자, UTF-8 약 4KB)에 넉넉한 여유를 둔다. */
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
	private static final List<String> SOURCE = List.of(CHUNK_ID, DOC_ID, RUN_SEQ, SEQ, LOC, CONTENT);

	private final RagProperties.Elasticsearch settings;
	private final RagProperties.Search search;
	private final RagTagProperties tags;
	private final WebClient client;
	private final ObjectMapper json;

	/** 태그 채널 없이 검색한다(테스트·평가용). */
	public ChunkSearcher(RagProperties properties, WebClient.Builder builder, ObjectMapper json) {
		this(properties, RagTagProperties.disabled(), builder, json);
	}

	@Autowired
	public ChunkSearcher(RagProperties properties, RagTagProperties tags, WebClient.Builder builder, ObjectMapper json) {
		this.settings = properties.elasticsearch();
		this.search = properties.search();
		this.tags = tags;
		// 기본 응답 버퍼(256KB)는 candidates를 늘리면 넘는다(청크 본문 최대 약 1300자 × 후보 수). 넘으면 정상 검색이 장애가 된다.
		this.client = builder.clone().baseUrl(settings.url()).codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)).build();
		this.json = json;
	}

	public List<Hit> search(ThreadDocumentScope scope, String query, float[] vector) {
		if (scope.targets().isEmpty()) return List.of();
		List<Object> filter = filter(scope);
		List<Hit> keyword = keyword(filter, query);
		List<Hit> semantic = vector(filter, vector);
		if (!tags.enabled()) return fuse(keyword, semantic);
		return fuse(List.of(semantic, keyword, tagged(scope, filter, query, vector, semantic)));
	}

	/**
	 * 태그 채널: 태그 색인을 같은 범위로 찾아 관련 문서 순위를 내고, 문서마다 대표 조각을 세운다. 대표는 벡터 채널에 그 문서 조각이 있으면
	 * 그중 1위, 없으면 그 문서(현재 회차)로 좁힌 벡터 검색 1건이다 — 이런 문서들의 검색은 한 요청(_msearch)으로 묶는다(문서마다 차례로
	 * 보내면 후보 수만큼 왕복이 늘어 검색이 느려진다). 대표가 최소 유사도에 못 미치면 그 문서는 뺀다. 실패하면 빈 채널이다.
	 */
	private List<Hit> tagged(ThreadDocumentScope scope, List<Object> filter, String query, float[] vector, List<Hit> semantic) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", tags.candidates());
		body.put("_source", List.of(TagIndexContract.DOC_ID, TagIndexContract.RUN_SEQ));
		body.put("query", Map.of("bool", Map.of("must", List.of(Map.of("multi_match", Map.of("query", query,
				"fields", List.of(TagIndexContract.KEYWORDS + "^2", TagIndexContract.SUMMARY), "minimum_should_match", tags.minimumShouldMatch()))),
				"filter", filter)));
		try {
			// 태그 순위대로 대표를 세운다. 벡터 채널에 없는 문서는 자리만 잡아 두고 한 번에 찾는다.
			List<Hit> ranked = new ArrayList<>();
			List<Map<String, Object>> lookups = new ArrayList<>();
			List<Integer> slots = new ArrayList<>();
			for (JsonNode tag : call(tags.alias(), body)) {
				String document = tag.path("_source").path(TagIndexContract.DOC_ID).asString("");
				int runSeq = tag.path("_source").path(TagIndexContract.RUN_SEQ).asInt();
				Hit reused = semantic.stream().filter(hit -> hit.document().equals(document) && hit.runSeq() == runSeq).findFirst().orElse(null);
				if (reused == null) {
					slots.add(ranked.size());
					lookups.add(bestChunk(scope, document, runSeq, vector));
				}
				ranked.add(reused);
			}
			List<List<JsonNode>> found = multiCall(settings.alias(), lookups);
			for (int i = 0; i < slots.size(); i++)
				for (JsonNode hit : found.get(i)) ranked.set(slots.get(i), hit(hit, cosine(hit), null));
			return ranked.stream().filter(java.util.Objects::nonNull).toList();
		} catch (RuntimeException error) {
			// 태그가 아직 없거나(태깅 꺼짐·첫 배포 직후) 태그 색인이 잠시 안 될 때. 본문 검색은 그대로 돌려준다.
			log.warn("태그 채널을 건너뛴다 — 본문 키워드·벡터 결과만 쓴다: {}", error.getMessage());
			return List.of();
		}
	}

	/** 한 문서(현재 회차) 안에서 질문과 의미가 가장 가까운 조각 1건을 찾는 검색. 최소 유사도에 못 미치면 결과가 없다. */
	private Map<String, Object> bestChunk(ThreadDocumentScope scope, String document, int runSeq, float[] vector) {
		Map<String, Object> knn = new LinkedHashMap<>();
		knn.put("field", EMB);
		knn.put("query_vector", vector);
		knn.put("k", 1);
		knn.put("num_candidates", search.numCandidates());
		knn.put("similarity", tags.chunkMinSimilarity());
		knn.put("filter", List.of(Map.of("term", Map.of(TNN_ID, scope.tenant().toString())),
				Map.of("terms", Map.of(THR_ID, scope.threads().threadIds().stream().map(Object::toString).toList())),
				Map.of("term", Map.of(DOC_ID, document)), Map.of("term", Map.of(RUN_SEQ, runSeq))));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", 1);
		body.put("_source", SOURCE);
		body.put("knn", knn);
		return body;
	}

	/** 고객사·방·(문서, 현재 회차) 쌍. 회차까지 거는 이유는 재처리 직후 이전 회차 청크가 정리 전까지 남아 있기 때문이다. */
	static List<Object> filter(ThreadDocumentScope scope) {
		// 방 문서 검색은 항상 방 조건을 건다. UNRESTRICTED·NONE이면 검색하지 않는다 — 방 조건이 빠지면 다른 방 문서가 섞인다.
		if (scope.threads().kind() != ThreadScopeFilter.Kind.THREADS)
			throw new IllegalArgumentException("방 문서 검색은 방 범위가 필요하다: " + scope.threads().kind());
		List<Object> documents = scope.targets().stream().<Object>map(target -> Map.of("bool", Map.of("filter", List.of(
				Map.of("term", Map.of(DOC_ID, target.document().toString())),
				Map.of("term", Map.of(RUN_SEQ, target.runSeq())))))).toList();
		return List.of(
				Map.of("term", Map.of(TNN_ID, scope.tenant().toString())),
				Map.of("terms", Map.of(THR_ID, scope.threads().threadIds().stream().map(Object::toString).toList())),
				Map.of("bool", Map.of("should", documents, "minimum_should_match", 1)));
	}

	private List<Hit> keyword(List<Object> filter, String query) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", search.candidates());
		body.put("_source", SOURCE);
		body.put("query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of(CONTENT, Map.of("query", query, "minimum_should_match", search.keywordMinimumShouldMatch())))), "filter", filter)));
		List<Hit> hits = new ArrayList<>();
		for (JsonNode hit : call(body)) hits.add(hit(hit, null, hit.path("_score").asDouble()));
		return hits;
	}

	private List<Hit> vector(List<Object> filter, float[] vector) {
		Map<String, Object> knn = new LinkedHashMap<>();
		knn.put("field", EMB);
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
		for (JsonNode hit : call(body)) hits.add(hit(hit, cosine(hit), null));
		return hits;
	}

	/** kNN _score(=(1+cos)/2)를 코사인 유사도로 되돌린다. 부동소수점 오차로 1을 살짝 넘을 수 있어 [-1, 1]로 자른다. */
	private static double cosine(JsonNode hit) {
		return Math.max(-1, Math.min(1, 2 * hit.path("_score").asDouble() - 1));
	}

	/** 두 채널의 순위를 RRF로 합친다. 같은 청크는 점수를 모으고, 문서당 상한을 지키며 topK개를 고른다. */
	List<Hit> fuse(List<Hit> keyword, List<Hit> semantic) {
		return fuse(List.of(semantic, keyword));
	}

	/** 채널들(앞쪽이 동점에서 앞선다)의 순위를 RRF로 합친다. */
	List<Hit> fuse(List<List<Hit>> channels) {
		Map<String, Hit> merged = new LinkedHashMap<>();
		Map<String, Double> scores = new HashMap<>();
		for (List<Hit> channel : channels) {
			for (int rank = 0; rank < channel.size(); rank++) {
				Hit hit = channel.get(rank);
				String key = hit.chunkId();
				merged.merge(key, hit, (a, b) -> new Hit(a.chunkId(), a.document(), a.runSeq(), a.seq(), a.loc(), a.content(),
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
		return call(settings.alias(), body);
	}

	/** 여러 검색을 한 요청(_msearch)으로 보낸다. 검색마다 결과 목록을 같은 순서로 돌려준다. 하나라도 실패하면 장애다. */
	private List<List<JsonNode>> multiCall(String alias, List<Map<String, Object>> bodies) {
		if (bodies.isEmpty()) return List.of();
		StringBuilder lines = new StringBuilder();
		for (Map<String, Object> body : bodies)
			lines.append(json.writeValueAsString(Map.of("index", alias))).append('\n').append(json.writeValueAsString(body)).append('\n');
		JsonNode responses = parse(post("/_msearch", MediaType.parseMediaType("application/x-ndjson"), lines.toString())).path("responses");
		if (!responses.isArray() || responses.size() != bodies.size())
			throw new RagUnavailableException("Elasticsearch 묶음 검색 응답의 결과 수가 요청과 다르다");
		List<List<JsonNode>> results = new ArrayList<>();
		for (JsonNode response : responses) {
			if (response.has("error")) throw new RagUnavailableException("Elasticsearch 묶음 검색 일부 실패: " + response.path("status").asInt());
			List<JsonNode> hits = new ArrayList<>();
			hits(response).forEach(hits::add);
			results.add(hits);
		}
		return results;
	}

	private Iterable<JsonNode> call(String alias, Map<String, Object> body) {
		return hits(parse(post("/" + alias + "/_search", MediaType.APPLICATION_JSON, body)));
	}

	private String post(String path, MediaType type, Object body) {
		try {
			return client.post().uri(path).contentType(type).bodyValue(body).retrieve().bodyToMono(String.class).block(settings.timeout());
		} catch (RuntimeException error) {
			// 별칭이 없는 404도 장애다 — 검색 대상 문서가 있는데 색인이 없다(인덱스 삭제·ES 볼륨 초기화). 다만 ETL이 빈 인덱스를 다시
			// 만든 뒤에는 404가 아니라 결과 0건(NO_MATCH)이 된다 — READY 문서의 청크 유실은 감지하지 못한다(INSTALL「방 문서 검색 준비 켜기」에 대처를 적었다).
			throw new RagUnavailableException("Elasticsearch 검색 실패: " + error.getClass().getSimpleName(), error);
		}
	}

	private JsonNode parse(String response) {
		try {
			return json.readTree(response == null ? "" : response);
		} catch (RuntimeException unreadable) {
			throw new RagUnavailableException("Elasticsearch 응답을 해석할 수 없다", unreadable);
		}
	}

	/** 검색 응답 하나의 결과 목록. 끝나지 않은 검색이나 결과 목록이 없는 응답은 장애다. */
	private static JsonNode hits(JsonNode tree) {
		if (tree.path("timed_out").asBoolean(false) || tree.path("_shards").path("failed").asInt(0) > 0)
			throw new RagUnavailableException("Elasticsearch 검색이 일부 샤드에서 끝나지 않았다");
		// 결과 목록이 없는 응답(빈 본문, 프록시 오류 페이지를 JSON으로 감싼 것 등)을 "결과 0건"으로 읽으면 장애가 근거 없음으로 숨는다.
		JsonNode hits = tree.path("hits").path("hits");
		if (!hits.isArray()) throw new RagUnavailableException("Elasticsearch 응답에 검색 결과 목록이 없다");
		return hits;
	}

	private static Hit hit(JsonNode hit, Double vectorScore, Double keywordScore) {
		JsonNode source = hit.path("_source");
		// chunk_id는 적재 때 정한 색인 문서 ID다. 다시 조립하지 않고 그대로 내보낸다(E가 출처로 저장하는 값).
		String document = source.path(DOC_ID).asString("");
		int runSeq = source.path(RUN_SEQ).asInt(), seq = source.path(SEQ).asInt();
		String chunkId = source.path(CHUNK_ID).asString("");
		// 비어 있으면(손으로 넣은 문서 등) 서로 다른 청크가 같은 키로 합쳐지지 않게 계약 규칙으로 만든다.
		if (chunkId.isEmpty() && !document.isEmpty()) chunkId = chunkId(UUID.fromString(document), runSeq, seq);
		return new Hit(chunkId, document, runSeq, seq, source.path(LOC).asString(""), source.path(CONTENT).asString(""), vectorScore, keywordScore);
	}
}
