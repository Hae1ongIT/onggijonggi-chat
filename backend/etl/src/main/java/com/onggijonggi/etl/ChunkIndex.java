package com.onggijonggi.etl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Description : 청크를 Elasticsearch에 적재·대조·삭제한다. 인덱스 매핑은 C(검색)가 소유하지만 C 착수 전에는 B가 기동·첫 적재 때
 *               최소 매핑(es-thr-doc-chunk-index.json)으로 인덱스와 별칭을 만든다. 쓰기·조회는 항상 별칭으로 한다.
 *               청크 문서에는 본문(content)을 함께 둬, 매핑·분석기 변경은 원본 재추출 없이 Elasticsearch 안에서 다시 색인한다.
 */
@Component
public class ChunkIndex {

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

	/** 별칭이 없으면 인덱스와 별칭을 만든다. 이미 있으면 아무것도 하지 않는다. */
	public void ensure() {
		if (ensured) return;
		try {
			try {
				client.get().uri("/_alias/{alias}", settings.alias()).retrieve().toBodilessEntity();
			} catch (HttpClientErrorException.NotFound missing) {
				ObjectNode body = (ObjectNode) mapping();
				body.putObject("aliases").putObject(settings.alias());
				try {
					client.put().uri("/{index}", settings.index()).contentType(MediaType.APPLICATION_JSON)
							.body(utf8(json.writeValueAsString(body))).retrieve().toBodilessEntity();
				} catch (HttpClientErrorException.BadRequest exists) {
					// 다른 워커가 먼저 만들었거나 인덱스만 있고 별칭이 없다. 별칭만 붙인다.
					client.put().uri("/{index}/_alias/{alias}", settings.index(), settings.alias()).retrieve().toBodilessEntity();
				}
			}
			ensured = true;
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/** 한 회차의 청크를 적재한다. 청크 ID가 문서 ID라 같은 회차를 다시 적재하면 덮어쓴다. */
	public void write(RunStore.Job job, List<Chunker.Chunk> chunks, List<float[]> vectors, String model) {
		ensure();
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
			response = json.readTree(client.post().uri("/{alias}/_bulk?refresh=wait_for", settings.alias())
					.contentType(NDJSON).body(utf8(ndjson.toString()))
					.retrieve().body(String.class));
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
		if (!response.path("errors").asBoolean(false)) return;
		for (JsonNode item : response.path("items")) {
			JsonNode result = item.path("index");
			int status = result.path("status").asInt(200);
			if (status < 300) continue;
			String reason = result.path("error").path("reason").asString("");
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
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/** 한 회차의 청크를 지운다. 멱등이다 — 인덱스가 아직 없으면 지울 것도 없다. */
	public void delete(UUID document, int runSeq) {
		try {
			client.post().uri("/{alias}/_delete_by_query?refresh=true&conflicts=proceed", settings.alias())
					.contentType(MediaType.APPLICATION_JSON).body(utf8(json.writeValueAsString(runQuery(document, runSeq))))
					.retrieve().toBodilessEntity();
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
