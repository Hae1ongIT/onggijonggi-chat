package com.onggijonggi.etl;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Class Name : IngestionPipeline.java
 * Description : 선점한 회차 하나를 끝까지 처리한다: 원본 읽기 → 추출 → 청킹 → 임베딩 → 적재 → 건수 대조 → READY 확정.
 *               단계 사이마다 선점 시한을 늘리며 문서가 아직 처리 중인지 본다 — 그사이 삭제됐으면 멈추고, 적재한 청크는
 *               정리 작업(RunSweeper)이 지운다. 일시 오류는 회차를 다음 시도로 미루고, 영구 오류·재시도 소진은 FAILED로 둔다.
 */
@Component
public class IngestionPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

	/** 처리 도중 문서가 삭제·취소됐다. 실패가 아니다. */
	private static final class Abandoned extends RuntimeException {
		Abandoned() {
			super(null, null, false, false);
		}
	}

	private final RunStore runs;
	private final SourceReader sources;
	private final TextExtractor extractor;
	private final Chunker chunker;
	private final EmbeddingClient embeddings;
	private final ChunkIndex index;
	private final EtlProperties properties;

	public IngestionPipeline(RunStore runs, SourceReader sources, TextExtractor extractor, Chunker chunker,
			EmbeddingClient embeddings, ChunkIndex index, EtlProperties properties) {
		this.runs = runs;
		this.sources = sources;
		this.extractor = extractor;
		this.chunker = chunker;
		this.embeddings = embeddings;
		this.index = index;
		this.properties = properties;
	}

	public void process(RunStore.Job job) {
		try {
			byte[] bytes = sources.read(job);
			alive(job);
			List<Chunker.Chunk> chunks = chunker.chunk(job.document(), job.runSeq(), extractor.extract(job.fileName(), bytes));
			if (chunks.isEmpty()) throw EtlFailure.permanent("EMPTY_TEXT", "청크 0건");
			alive(job);
			List<float[]> vectors = embeddings.embed(chunks.stream().map(Chunker.Chunk::content).toList(), () -> alive(job));
			index.write(job, chunks, vectors, embeddings.model());
			long stored = index.count(job.document(), job.runSeq());
			// 적재 결과가 청크 수와 다르면 READY로 숨기지 않는다(지도 선택 11). 다음 시도가 같은 ID로 덮어쓴다.
			if (stored != chunks.size())
				throw EtlFailure.transientFailure("INDEX_COUNT", "적재 " + stored + "건 / 청크 " + chunks.size() + "건", null);
			if (!runs.complete(job, chunks.size(), embeddings.model(), embeddings.dimensions(), properties.chunk().fingerprint())) {
				log.info("처리 중 삭제된 문서라 결과를 버린다: doc={} run={}", job.document(), job.runSeq());
				return;
			}
			log.info("문서 처리 완료: doc={} run={} chunks={}", job.document(), job.runSeq(), chunks.size());
		} catch (Abandoned abandoned) {
			runs.cancel(job);
			log.info("처리 중 삭제된 문서라 멈춘다: doc={} run={}", job.document(), job.runSeq());
		} catch (EtlFailure failure) {
			handle(job, failure);
		} catch (RuntimeException unexpected) {
			handle(job, EtlFailure.transientFailure("UNEXPECTED", String.valueOf(unexpected.getMessage()), unexpected));
		}
	}

	private void handle(RunStore.Job job, EtlFailure failure) {
		List<Duration> delays = properties.retryDelays();
		if (!failure.permanent() && job.attempts() <= delays.size()) {
			Duration delay = delays.get(job.attempts() - 1);
			runs.retryLater(job, failure.code(), delay);
			log.warn("문서 처리 일시 실패 — {} 뒤 다시 시도({}회째): doc={} run={} {}", delay, job.attempts(), job.document(), job.runSeq(),
					failure.getMessage(), failure.getCause());
			return;
		}
		// 재시도를 다 쓰면 마지막 일시 오류 코드를 함께 남긴다 — 내부 key 불일치(SOURCE_UNAUTHORIZED) 같은 원인을 DB에서 볼 수 있게 한다.
		String code = failure.permanent() ? failure.code() : truncate("RETRY_EXHAUSTED:" + failure.code());
		runs.fail(job, code);
		log.error("문서 처리 실패({}): doc={} run={} {}", code, job.document(), job.runSeq(), failure.getMessage(), failure.getCause());
	}

	/** thr_doc_run.err는 64자다. */
	private static String truncate(String code) {
		return code.length() > 64 ? code.substring(0, 64) : code;
	}

	private void alive(RunStore.Job job) {
		if (!runs.extend(job, properties.lease())) throw new Abandoned();
	}
}
