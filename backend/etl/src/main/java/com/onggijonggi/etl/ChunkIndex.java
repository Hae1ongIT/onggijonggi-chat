package com.onggijonggi.etl;

import static com.onggijonggi.common.document.ChunkIndexContract.*;

import com.onggijonggi.common.document.Chunker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
	/** 이전 작업을 한 번에 기다리는 상한. 넘으면 일시 실패로 끝나고 다음 시도가 같은 작업을 이어서 기다린다. */
	private static final Duration MIGRATION_WAIT = Duration.ofHours(2);
	/** 이전 작업 상태 조회가 이만큼 연달아 실패하면 일시 실패로 끝낸다. */
	private static final int POLL_FAILURES = 5;
	/** 이전 작업 상태를 보는 간격. */
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
	/** 문자셋을 붙인다 — 붙이지 않으면 문자열 본문이 ISO-8859-1로 인코딩돼 한글 청크가 "??"로 저장된다. */
	private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

	private final RestClient client;
	private final ObjectMapper json;
	private final EtlProperties.Elasticsearch settings;
	private volatile boolean ensured;
	/** 인덱스 준비·이전 잠금. 정리 작업의 삭제도 이 잠금으로 이전이 끝나기를 기다린다. */
	private final Object preparing = new Object();
	/** 기다리던 이전 작업. 상태 조회가 끊겨 이전이 일시 실패로 끝나도 다음 시도는 새로 복사하지 않고 이 작업을 이어서 기다린다. */
	private volatile String pendingTask;

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
	public void ensure() {
		// 준비가 끝났으면 잠금 없이 돌아간다 — 평소의 적재가 정리 작업(delete)과 서로 기다리지 않게 한다.
		if (ensured) return;
		synchronized (preparing) {
			if (ensured) return;
			prepare();
		}
	}

	private void prepare() {
		try {
			JsonNode aliases;
			try {
				aliases = json.readTree(client.get().uri("/_alias/{alias}", settings.alias()).retrieve().body(String.class));
			} catch (HttpClientErrorException.NotFound missing) {
				create(true);
				ready();
				return;
			}
			List<String> previous = new ArrayList<>(aliases.propertyNames());
			if (previous.isEmpty()) create(true);
			else if (!previous.contains(settings.index())) migrate(previous);
			else if (previous.size() > 1) detach(previous);
			ready();
		} catch (EtlFailure failure) {
			throw failure;
		} catch (RuntimeException error) {
			throw HttpCalls.classify("INDEX", error);
		}
	}

	/**
	 * 준비가 끝났다. 기다리던 이전 작업도 더는 의미가 없다 — 상태 조회가 끊긴 뒤 운영자가 별칭을 고치는 등으로 이전을 거치지 않고
	 * 준비가 끝나면 남은 작업 ID 때문에 청크 삭제가 영영 미뤄진다.
	 */
	private void ready() {
		pendingTask = null;
		ensured = true;
	}

	/** 인덱스 이전 작업이 진행 중인가(상태 조회가 끊겨 다음 시도를 기다리는 중 포함). 정리 작업은 이때 지우지 않고 기다린다. */
	public boolean migrating() {
		return pendingTask != null;
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
		// 작업으로 띄우고 끝날 때까지 상태를 본다. 응답을 기다리게 하면 청크가 많을 때 요청 시간 제한(기본 60초)에 끊기고, ES 쪽 작업은
		// 계속 도는데 다음 시도가 같은 복사를 처음부터 또 시작한다.
		if (pendingTask == null) {
			String task = json.readTree(client.post().uri("/_reindex?wait_for_completion=false&refresh=true")
					.contentType(MediaType.APPLICATION_JSON)
					.body(utf8(json.writeValueAsString(Map.of("source", Map.of("index", previous), "dest", Map.of("index", settings.index())))))
					.retrieve().body(String.class)).path("task").asString("");
			if (task.isEmpty()) throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기 작업을 시작하지 못했다", null);
			pendingTask = task;
		}
		JsonNode status = waitFor(pendingTask);
		pendingTask = null;
		if (status.has("error"))
			throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기가 실패했다: " + HttpCalls.abbreviate(status.path("error").toString()), null);
		JsonNode result = status.path("response");
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

	/** ES 작업이 끝날 때까지 1초 간격으로 본다. 끝난 작업의 상태(response 또는 error)를 돌려준다. 한 번 기다리는 상한은 MIGRATION_WAIT다. */
	private JsonNode waitFor(String task) {
		long deadline = System.nanoTime() + MIGRATION_WAIT.toNanos();
		int failures = 0;
		while (true) {
			try {
				JsonNode status = json.readTree(client.get().uri("/_tasks/{task}", task).retrieve().body(String.class));
				if (status.path("completed").asBoolean(false)) return status;
				failures = 0;
			} catch (HttpClientErrorException.NotFound gone) {
				// 작업 기록이 없다(ES 재시작 등). 다음 시도에서 처음부터 다시 옮긴다.
				pendingTask = null;
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기 작업을 찾지 못했다: " + task, gone);
			} catch (RuntimeException error) {
				// 상태 조회가 잠깐 끊긴 것은 작업 실패가 아니다. 연속으로 실패할 때만 일시 실패로 끝낸다(작업은 이어서 기다린다).
				if (++failures >= POLL_FAILURES) throw HttpCalls.classify("INDEX", error);
			}
			if (System.nanoTime() > deadline)
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기가 " + MIGRATION_WAIT.toMinutes() + "분 안에 끝나지 않았다: " + task, null);
			try {
				Thread.sleep(POLL_INTERVAL.toMillis());
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 옮기기를 기다리다 중단됐다(ES 작업은 계속 돈다): " + task, interrupted);
			}
		}
	}

	private long indexCount(String index) {
		return json.readTree(client.get().uri("/{index}/_count", index).retrieve().body(String.class)).path("count").asLong();
	}

	/** 매핑의 벡터 차원. 임베딩 설정과 다르면 워커가 처리를 시작하지 않는다. */
	public int mappingDimensions() {
		return mapping().path("mappings").path("properties").path(EMB).path("dims").asInt(-1);
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
			source.put(CHUNK_ID, chunk.id());
			source.put(DOC_ID, job.document().toString());
			source.put(THR_ID, job.thread().toString());
			source.put(TNN_ID, job.tenant().toString());
			source.put(RUN_SEQ, job.runSeq());
			source.put(SEQ, chunk.seq());
			source.put(CONTENT, chunk.content());
			source.put(LOC, chunk.loc());
			source.put(EMB, vectors.get(i));
			source.put(EMB_MDL, model);
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
		// 정리 작업은 처리 스레드와 따로 돈다. 인덱스 준비·이전 잠금을 잡아 옮기는 동안에는 기다린다 — 옮기기 전 인덱스에서만 지우면 이미
		// 복사된 청크가 새 인덱스에 남는다. ensure를 직접 부르지는 않는다(정리 작업이 인덱스를 새로 만들지 않게). 준비가 끝난 뒤의
		// 적재는 이 잠금을 잡지 않아 삭제와 서로 기다리지 않는다.
		synchronized (preparing) {
			// 이전 작업이 아직 ES에서 돌고 있다(상태 조회가 끊겨 잠금이 풀린 경우). 지금 이전 전 인덱스에서 지우면 이미 복사 대상에 든
			// 청크가 새 인덱스에 남아 영영 지워지지 않는다. 일시 실패로 미뤄 이전이 끝난 뒤 새 인덱스에서 지운다.
			if (pendingTask != null)
				throw EtlFailure.transientFailure("INDEX_UNAVAILABLE", "검색 인덱스 이전이 끝나지 않아 청크 삭제를 미룬다", null);
			deleteRun(document, runSeq);
		}
	}

	private void deleteRun(UUID document, int runSeq) {
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
				Map.of("term", Map.of(DOC_ID, document.toString())),
				Map.of("term", Map.of(RUN_SEQ, runSeq))))));
	}

	private JsonNode mapping() {
		try (InputStream in = new ClassPathResource(MAPPING).getInputStream()) {
			return json.readTree(in);
		} catch (IOException error) {
			throw new IllegalStateException("인덱스 매핑 리소스를 읽지 못했다: " + MAPPING, error);
		}
	}
}
