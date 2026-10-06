package com.onggijonggi.etl;

import com.onggijonggi.common.document.Chunker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Class Name : ChunkIndex.java
 * Description : 청크를 Elasticsearch에 적재·대조·삭제한다. 인덱스 매핑(공용 모듈의 es-thr-doc-chunk-index.json)은 검색(#344)이
 *               소유하고, ETL은 기동·첫 적재 때 그 매핑으로 인덱스와 별칭을 만들거나 이전 인덱스에서 옮긴다. 쓰기·조회는 항상 별칭으로 한다.
 *               청크 문서에는 본문(content)을 함께 둬, 매핑·분석기 변경은 원본 재추출 없이 Elasticsearch 안에서 다시 색인한다.
 */
@Component
public class ChunkIndex {

	private static final Logger log = LoggerFactory.getLogger(ChunkIndex.class);
	private static final String MAPPING = "es-thr-doc-chunk-index.json";
	/** 문자셋을 붙인다 — 붙이지 않으면 문자열 본문이 ISO-8859-1로 인코딩돼 한글 청크가 "??"로 저장된다. */
	private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

	private final RestClient client;
	private final ObjectMapper json;
	private final EtlProperties.Elasticsearch settings;
	private volatile boolean ensured;

	public ChunkIndex(EtlProperties properties, ObjectMapper json) {
		this.settings = properties.elasticsearch();
		this.client = HttpCalls.client(settings.url(), properties.requestTimeout());
		this.json = json;
	}

	/**
	 * 별칭이 없으면 인덱스와 별칭을 만든다. 별칭이 설정한 인덱스가 아닌 이전 인덱스를 가리키면(매핑·분석기를 바꿔 인덱스 이름을
	 * 올린 배포) 새 인덱스를 만들어 이전 인덱스의 청크를 _reindex로 옮긴 뒤 별칭을 한 번에 넘긴다. 본문을 새 분석기로 다시 분석하고
	 * 벡터는 그대로 옮기므로 원본 재추출·재임베딩이 없다. 이전 인덱스는 지우지 않는다(확인 뒤 운영자가 지운다). 옮기는 동안 검색은
	 * 이전 인덱스로 계속 된다. 처리 스레드가 함께 부르므로 직렬화한다.
	 */
	public synchronized void ensure() {
		if (ensured) return;
		try {
			JsonNode aliases;
			try {
				aliases = json.readTree(client.get().uri("/_alias/{alias}", settings.alias()).retrieve().body(String.class));
			} catch (HttpClientErrorException.NotFound missing) {
				create(true);
				ensured = true;
				return;
			}
			List<String> previous = new ArrayList<>(aliases.propertyNames());
			if (previous.isEmpty()) create(true);
			else if (!previous.contains(settings.index())) migrate(previous);
			else if (previous.size() > 1) detach(previous);
			ensured = true;
		} catch (EtlFailure failure) {
			throw failure;
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/** 설정한 인덱스를 공용 매핑으로 만든다. withAlias면 별칭도 함께 붙인다. 이미 있으면 그대로 쓴다. */
	private void create(boolean withAlias) {
		ObjectNode body = (ObjectNode) mapping();
		if (withAlias) body.putObject("aliases").putObject(settings.alias());
		try {
			client.put().uri("/{index}", settings.index()).contentType(MediaType.APPLICATION_JSON)
					.body(utf8(json.writeValueAsString(body))).retrieve().toBodilessEntity();
		} catch (HttpClientErrorException.BadRequest rejected) {
			String reason = rejected.getResponseBodyAsString();
			// 다른 워커가 먼저 만들었거나 인덱스만 있고 별칭이 없다. 별칭만 붙인다. 그 밖의 400(nori 플러그인 없음, 매핑 오류)은
			// 원인을 그대로 남기고 영구 실패로 둔다 — 별칭 붙이기로 넘어가면 진짜 원인이 "인덱스 없음"으로 가려진다.
			if (!reason.contains("resource_already_exists_exception"))
				throw EtlFailure.permanent("INDEX_REJECTED", "인덱스 생성 거절: " + HttpCalls.abbreviate(reason), rejected);
			if (withAlias) client.put().uri("/{index}/_alias/{alias}", settings.index(), settings.alias()).retrieve().toBodilessEntity();
		}
	}

	/** 이전 인덱스의 청크를 새 인덱스로 옮기고 별칭을 원자적으로 넘긴다. 건수가 맞지 않으면 넘기지 않는다(다음 시도에서 다시). */
	private void migrate(List<String> previous) {
		log.info("검색 인덱스를 옮긴다: {} → {}", previous, settings.index());
		create(false);
		JsonNode result = json.readTree(client.post().uri("/_reindex?wait_for_completion=true&refresh=true")
				.contentType(MediaType.APPLICATION_JSON)
				.body(utf8(json.writeValueAsString(Map.of("source", Map.of("index", previous), "dest", Map.of("index", settings.index())))))
				.retrieve().body(String.class));
		if (result.path("timed_out").asBoolean(false) || !result.path("failures").isEmpty())
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기가 끝나지 않았다: " + HttpCalls.abbreviate(result.toString()), null);
		long before = 0;
		for (String index : previous) before += indexCount(index);
		long after = indexCount(settings.index());
		if (after < before)
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "옮긴 청크 수(" + after + ")가 이전 인덱스(" + before + ")보다 적다", null);
		// 많은 것은 막지 않는다 — 앞선 시도가 남긴 청크일 수 있고, 막으면 이전이 영원히 끝나지 않는다. 지난 회차 청크라면 검색 조건
		// (문서, 현재 회차)이 거르고 정리 작업이 지운다.
		if (after > before) log.warn("옮긴 청크 수({})가 이전 인덱스({})보다 많다 — 앞선 이전 시도의 잔여일 수 있다", after, before);
		List<Object> actions = new ArrayList<>();
		for (String index : previous) actions.add(Map.of("remove", Map.of("index", index, "alias", settings.alias())));
		actions.add(Map.of("add", Map.of("index", settings.index(), "alias", settings.alias())));
		client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON)
				.body(utf8(json.writeValueAsString(Map.of("actions", actions)))).retrieve().toBodilessEntity();
		log.info("검색 인덱스를 옮겼다: 청크 {}개, 별칭 {} → {}. 이전 인덱스 {}는 확인 뒤 지운다", after, settings.alias(), settings.index(), previous);
	}

	/**
	 * 별칭이 설정한 인덱스와 다른 인덱스를 함께 가리킨다(운영자가 손으로 붙인 경우). 그대로 두면 별칭으로 쓰는 bulk가 "쓰기 인덱스
	 * 없음"으로 거절돼 모든 문서가 실패하고, 검색은 같은 청크를 두 번 센다. 다른 인덱스를 별칭에서 한 번에 뗀다(지우지는 않는다).
	 */
	private void detach(List<String> previous) {
		List<Object> actions = new ArrayList<>();
		for (String index : previous)
			if (!index.equals(settings.index())) actions.add(Map.of("remove", Map.of("index", index, "alias", settings.alias())));
		client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON)
				.body(utf8(json.writeValueAsString(Map.of("actions", actions)))).retrieve().toBodilessEntity();
		log.warn("검색 별칭이 여러 인덱스를 가리켜 {} 외의 인덱스를 뗐다: {}", settings.index(), previous);
	}

	private long indexCount(String index) {
		return json.readTree(client.get().uri("/{index}/_count", index).retrieve().body(String.class)).path("count").asLong();
	}

	/** 매핑의 벡터 차원. 임베딩 설정과 다르면 워커가 처리를 시작하지 않는다. */
	public int mappingDimensions() {
		return mapping().path("mappings").path("properties").path("emb").path("dims").asInt(-1);
	}

	/**
	 * 한 회차의 청크를 적재한다. 청크 ID가 문서·회차·순번(doc:run:seq)으로 정해져 같은 회차를 다시 적재하면 덮어쓴다. 큰 문서는 청크가 수천 개라(청크당 벡터 약
	 * 10KB) 한 요청이 수십 MB가 되므로 bulkSize개씩 나눠 보낸다. 중간에 실패하면 다음 시도가 같은 ID로 처음부터 덮어쓴다.
	 */
	public void write(RunStore.Job job, List<Chunker.Chunk> chunks, List<float[]> vectors, String model) {
		ensure();
		for (int from = 0; from < chunks.size(); from += settings.bulkSize()) {
			int to = Math.min(chunks.size(), from + settings.bulkSize());
			writeBatch(job, chunks.subList(from, to), vectors.subList(from, to), model);
		}
	}

	private void writeBatch(RunStore.Job job, List<Chunker.Chunk> chunks, List<float[]> vectors, String model) {
		StringBuilder ndjson = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			Chunker.Chunk chunk = chunks.get(i);
			Map<String, Object> source = new LinkedHashMap<>();
			source.put("chunk_id", chunk.id());
			source.put("doc_id", job.document().toString());
			source.put("thr_id", job.thread().toString());
			source.put("tnn_id", job.tenant().toString());
			source.put("run_seq", job.runSeq());
			source.put("seq", chunk.seq());
			source.put("content", chunk.content());
			source.put("loc", chunk.loc());
			source.put("emb", vectors.get(i));
			source.put("emb_mdl", model);
			ndjson.append(json.writeValueAsString(Map.of("index", Map.of("_id", chunk.id())))).append('\n');
			ndjson.append(json.writeValueAsString(source)).append('\n');
		}
		JsonNode response;
		try {
			// require_alias: 별칭이 사라졌으면(운영자가 인덱스를 지움) ES가 매핑 없는 같은 이름의 인덱스를 자동으로 만들지 않고 404를 준다.
			response = json.readTree(client.post().uri("/{alias}/_bulk?refresh=wait_for&require_alias=true", settings.alias())
					.contentType(NDJSON).body(utf8(ndjson.toString()))
					.retrieve().body(String.class));
		} catch (HttpClientErrorException.NotFound aliasMissing) {
			ensured = false;
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 별칭이 없다 — 다음 시도에서 다시 만든다", aliasMissing);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
		if (!response.path("errors").asBoolean(false)) return;
		for (JsonNode item : response.path("items")) {
			JsonNode result = item.path("index");
			int status = result.path("status").asInt(200);
			if (status < 300) continue;
			String reason = result.path("error").path("reason").asString("");
			if (status == 404) {
				// require_alias: 별칭이 사라졌다(ES는 bulk 전체가 아니라 항목마다 404로 알린다). 다음 시도에서 인덱스를 다시 만든다.
				ensured = false;
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 별칭이 없다 — 다음 시도에서 다시 만든다: " + reason, null);
			}
			if (status == 429 || status >= 500) throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "bulk " + status + " " + reason, null);
			throw EtlFailure.permanent("INDEX_REJECTED", "bulk " + status + " " + reason);
		}
	}

	/** 한 회차의 적재된 청크 수. */
	public long count(UUID document, int runSeq) {
		try {
			JsonNode response = json.readTree(client.post().uri("/{alias}/_count", settings.alias())
					.contentType(MediaType.APPLICATION_JSON).body(utf8(json.writeValueAsString(runQuery(document, runSeq))))
					.retrieve().body(String.class));
			return response.path("count").asLong(-1);
		} catch (HttpClientErrorException.NotFound aliasMissing) {
			// 적재와 대조 사이에 인덱스가 지워졌다. 다음 시도에서 다시 만들어 처음부터 적재한다.
			ensured = false;
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 별칭이 없다 — 다음 시도에서 다시 만든다", aliasMissing);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/** 한 회차의 청크를 지운다. 멱등이다 — 인덱스가 아직 없으면 지울 것도 없다. */
	public void delete(UUID document, int runSeq) {
		// 정리 작업은 처리 스레드와 따로 돈다. 인덱스를 옮기는 중이면 끝날 때까지 기다린다 — 옮기기 전 인덱스에서만 지우면 이미 복사된
		// 청크가 새 인덱스에 남는다.
		ensure();
		try {
			JsonNode response = json.readTree(client.post().uri("/{alias}/_delete_by_query?refresh=true&conflicts=proceed", settings.alias())
					.contentType(MediaType.APPLICATION_JSON).body(utf8(json.writeValueAsString(runQuery(document, runSeq))))
					.retrieve().body(String.class));
			// 200이어도 일부 실패·시간 초과가 있을 수 있다. 그때 정리 완료로 표시하면 지난 회차 청크가 남는다.
			if (response.path("timed_out").asBoolean(false) || !response.path("failures").isEmpty())
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "청크 삭제가 끝나지 않았다: " + HttpCalls.abbreviate(response.toString()), null);
		} catch (HttpClientErrorException.NotFound noIndex) {
			// 아직 한 번도 적재한 적이 없다.
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/** 본문은 항상 UTF-8 바이트로 보낸다(위 NDJSON 참고). */
	private static byte[] utf8(String body) {
		return body.getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> runQuery(UUID document, int runSeq) {
		return Map.of("query", Map.of("bool", Map.of("filter", List.of(
				Map.of("term", Map.of("doc_id", document.toString())),
				Map.of("term", Map.of("run_seq", runSeq))))));
	}

	private JsonNode mapping() {
		try (InputStream in = new ClassPathResource(MAPPING).getInputStream()) {
			return json.readTree(in);
		} catch (IOException error) {
			throw new IllegalStateException("인덱스 매핑 리소스를 읽지 못했다: " + MAPPING, error);
		}
	}
}
