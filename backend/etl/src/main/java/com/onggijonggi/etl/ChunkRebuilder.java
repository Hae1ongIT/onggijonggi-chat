package com.onggijonggi.etl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : ChunkRebuilder.java
 * Description : READY 문서의 검색 조각을 다시 만들 일을 찾아 회차를 만든다(#348). 처리는 기존 워커가 한다 — 사용자 문서(INGEST)를 먼저
 *               집으므로 여기서 회차를 많이 만들어도 새 문서가 밀리지 않고, 임베딩 서버 부담도 워커 동시성 그대로다.
 *               - 운영자 요청(doc_rbl): 전체·설정이 바뀐 문서를 다시 만든다.
 *               - 설정 변경: 기동 뒤 한 번, 현재 회차의 임베딩 모델·차원·청킹 설정이 지금 설정과 다른 문서를 다시 만든다(배포만으로 끝난다).
 *               - 자동 복구: 주기마다 현재 회차의 청크 수를 ES와 대조해 빠진 문서를 다시 만든다(ES 볼륨 삭제·서버 이전).
 *               ES에 닿지 않거나 집계가 불완전하면 그 주기는 건너뛴다 — 장애를 "조각 없음"으로 오인해 전체를 다시 만들지 않는다.
 *               처리가 꺼져 있거나(임베딩 미설정) 인덱스 준비·이전이 끝나지 않았으면 아무것도 하지 않는다.
 *               상태 필드는 tick()(fixedDelay라 자기 자신과 겹치지 않는다)에서만 바꾼다.
 */
@Component
public class ChunkRebuilder {

	private static final Logger log = LoggerFactory.getLogger(ChunkRebuilder.class);
	/** 자동으로 한 주기에 만드는 회차 수 상한(설정 변경·자동 복구 각각). 남은 것은 다음 주기에 이어서 한다. */
	static final int AUTO_LIMIT = 200;
	/** 종류별 대기 회차가 이만큼 넘으면 그 종류의 자동 회차(REBUILD는 설정 변경, RECOVER는 자동 복구)를 만들지 않고 줄기를 기다린다 —
	 *  워커(문서 2개씩)보다 빨리 쌓으면 대기 회차만 늘어 선점·정리 비용이 커진다. 운영자 요청은 이 상한과 무관하다. */
	static final int BACKLOG_LIMIT = 1000;
	/** 자동 복구가 ES에 한 번에 묻는 문서 수. */
	static final int VERIFY_PAGE = 200;

	private final RebuildStore rebuilds;
	private final ChunkIndex index;
	private final EmbeddingClient embeddings;
	private final IngestionWorkers workers;
	private final EtlProperties properties;
	private final Duration verifyDelay;
	/** 설정이 바뀐 문서를 다 찾았는가. 기동마다 다시 본다. */
	private boolean outdatedQueued;
	/** 마지막 자동 복구 대조 시각(nanoTime). 0이면 아직 한 번도 하지 않았다 — 준비가 끝나면 바로 한 번 한다. */
	private long lastVerified;
	/** 대조 중 별칭이 사라진 것을 봤다. 다음 주기에 인덱스를 다시 만든 뒤 대조한다(빈 인덱스 → 모든 문서 복구). */
	private boolean indexLost;
	/** 상한에서 멈춘 대조를 이어서 볼 위치(마지막으로 본 문서). null이면 처음부터. */
	private UUID resumeAfter;
	/** 자동 경로를 미루고 있는 이유. 바뀔 때만 로그로 남긴다. null이면 돌고 있다. */
	private String blockedReason;

	public ChunkRebuilder(RebuildStore rebuilds, ChunkIndex index, EmbeddingClient embeddings, IngestionWorkers workers,
			EtlProperties properties, @Value("${app.etl.verify-delay:10m}") Duration verifyDelay) {
		this.rebuilds = rebuilds;
		this.index = index;
		this.embeddings = embeddings;
		this.workers = workers;
		this.properties = properties;
		this.verifyDelay = verifyDelay;
	}

	@Scheduled(fixedDelayString = "${app.etl.rebuild-delay:30s}", initialDelayString = "${app.etl.rebuild-delay:30s}")
	public void tick() {
		// 처리가 꺼진 것은 IngestionWorkers가 주기적으로 알린다.
		if (!workers.isRunning()) return;
		if (index.migrating()) {
			blocked("검색 인덱스 이전이 끝나지 않았다");
			return;
		}
		if (!index.prepared()) {
			// 인덱스가 지워진 것을 대조 중에 봤으면 여기서 다시 만든다(별칭이 없으면 생성만 하므로 빠르다). 그 밖에는 기동 준비를 기다린다.
			if (!indexLost) {
				blocked("검색 인덱스 준비가 끝나지 않았다");
				return;
			}
			try {
				index.ensure();
				indexLost = false;
			} catch (RuntimeException error) {
				log.warn("지워진 검색 인덱스를 다시 만들지 못했다 — 다음 주기에 다시 본다: {}", error.getMessage());
				return;
			}
		}
		handleRequests();
		// 상한은 종류별로 본다 — 운영자 전체 재처리로 REBUILD가 수만 건 쌓여도 자동 복구(조각이 사라져 검색이 빈 문서)는 멈추지 않는다.
		boolean rebuildFull = backlogFull(RunStore.REBUILD);
		boolean recoverFull = backlogFull(RunStore.RECOVER);
		if (rebuildFull || recoverFull)
			blocked("대기 회차가 " + BACKLOG_LIMIT + "건을 넘었다(" + (rebuildFull ? "설정 변경·운영자 다시 만들기" : "자동 복구")
					+ (rebuildFull && recoverFull ? "·자동 복구" : "") + ") — 줄 때까지 그쪽 자동 회차를 만들지 않는다");
		else blocked(null);
		if (!rebuildFull) queueOutdated();
		if (!recoverFull && (lastVerified == 0 || System.nanoTime() - lastVerified >= verifyDelay.toNanos())) {
			lastVerified = System.nanoTime();
			verify();
		}
	}

	private boolean backlogFull(String kind) {
		try {
			return rebuilds.backlog(kind) >= BACKLOG_LIMIT;
		} catch (RuntimeException error) {
			log.warn("다시 만들기 대기 수를 읽지 못했다 — 다음 주기에 다시 본다", error);
			return true;
		}
	}

	/**
	 * 자동 경로를 미루는 이유가 바뀔 때만 한 줄 남긴다(null이면 다시 돈다). 미루는 동안 조용하면 "문제없음"과 "멈춤"이 구분되지 않는다.
	 */
	private void blocked(String reason) {
		if (Objects.equals(reason, blockedReason)) return;
		if (reason != null) log.info("다시 만들기를 미룬다: {}", reason);
		else if (blockedReason != null) log.info("다시 만들기를 다시 시작한다(미룬 이유: {})", blockedReason);
		blockedReason = reason;
	}

	private void handleRequests() {
		try {
			RebuildStore.HandledRequest handled;
			while ((handled = rebuilds.handleNextRequest(embeddings.model(), embeddings.dimensions(), chunking())) != null)
				log.info("일괄 재처리 요청을 받았다: request={} 범위={} 대상 {}건", handled.request().id(), handled.request().kind(), handled.targets());
		} catch (RuntimeException error) {
			log.warn("일괄 재처리 요청을 처리하지 못했다 — 다음 주기에 다시 본다", error);
		}
	}

	/**
	 * 설정이 바뀐 문서를 남지 않을 때까지 상한씩 다시 만든다. 그 순간 진행 중 회차가 있던 문서는 그 처리가 지금 설정으로 끝나므로 빠져도
	 * 된다. 다시 만들기가 실패한 문서는 이전 설정 조각으로 검색되며 재기동이나 운영자 요청(OUTDATED) 때 다시 시도한다 — 실패한 문서를
	 * 주기마다 다시 잡아 임베딩 서버를 계속 두드리지 않게 한다.
	 */
	private void queueOutdated() {
		if (outdatedQueued) return;
		try {
			int queued = rebuilds.queueOutdated(embeddings.model(), embeddings.dimensions(), chunking(), AUTO_LIMIT);
			if (queued > 0) log.info("처리 설정(임베딩 모델·차원·청킹)이 바뀐 문서 {}건을 다시 만든다", queued);
			// 하나도 만들지 못했을 때만 끝으로 본다 — 같은 순간 사용자 처리와 겹쳐 상한보다 적게 만들어져도 남은 문서가 있을 수 있다.
			if (queued == 0) {
				outdatedQueued = true;
				log.info("처리 설정이 바뀐 문서 찾기를 마쳤다 — 다음 기동 때 다시 본다");
			}
		} catch (RuntimeException error) {
			log.warn("처리 설정이 바뀐 문서를 찾지 못했다 — 다음 주기에 다시 본다", error);
		}
	}

	/**
	 * 현재 회차의 청크가 ES에 모두 있는지 문서 묶음마다 대조하고, 빠진 문서에 RECOVER 회차를 만든다. 한 번에 AUTO_LIMIT개까지 만들고,
	 * 상한에 닿으면 거기서 멈췄다가 다음 주기에 그 다음 문서부터 이어서 본다(처음부터 다시 훑지 않는다).
	 */
	void verify() {
		int checked = 0;
		int queued = 0;
		UUID after = resumeAfter;
		try {
			while (queued < AUTO_LIMIT) {
				List<RebuildStore.CurrentRun> page = rebuilds.currentRuns(after, VERIFY_PAGE);
				if (page.isEmpty()) break;
				Map<UUID, Long> counts = index.runCounts(page.stream()
						.collect(Collectors.toMap(RebuildStore.CurrentRun::document, RebuildStore.CurrentRun::runSeq)));
				for (RebuildStore.CurrentRun run : page) {
					checked++;
					after = run.document();
					if (counts.getOrDefault(run.document(), 0L) < run.chunks() && rebuilds.queueRecover(run)) queued++;
					if (queued >= AUTO_LIMIT) break;
				}
				if (queued < AUTO_LIMIT && page.size() < VERIFY_PAGE) {
					after = null;
					break;
				}
			}
		} catch (EtlFailure failure) {
			if (!index.prepared()) {
				// 인덱스가 지워졌다. 다음 주기에 다시 만든 뒤 대조 주기를 기다리지 않고 처음부터 바로 대조해 빠진 문서를 복구한다.
				indexLost = true;
				lastVerified = 0;
				resumeAfter = null;
			}
			log.warn("검색 조각 대조를 건너뛴다 — {} 다시 본다: {}", indexLost ? "인덱스를 다시 만든 뒤" : readable(verifyDelay) + " 뒤", failure.getMessage());
			return;
		} catch (RuntimeException error) {
			log.warn("검색 조각 대조를 건너뛴다 — {} 뒤 다시 본다", readable(verifyDelay), error);
			return;
		}
		if (queued > 0) log.warn("검색 조각이 빠진 문서 {}건을 다시 만든다(대조 {}건)", queued, checked);
		if (queued < AUTO_LIMIT) log.info("검색 조각 대조를 마쳤다: 이번 대조 {}건, 빠진 문서 {}건 — {} 뒤 다시 본다", checked, queued, readable(verifyDelay));
		// 상한에서 멈췄으면 대조 주기를 기다리지 않고 다음 주기에 이어서 본다(ES를 비운 뒤 문서가 많을 때).
		resumeAfter = queued >= AUTO_LIMIT ? after : null;
		if (resumeAfter != null) lastVerified = 0;
	}

	/** 로그용 시간 표기(10분, 30초). Duration.toString(PT10M)은 운영자가 읽기 어렵다. */
	static String readable(Duration duration) {
		long seconds = duration.toSeconds();
		return seconds % 60 == 0 && seconds >= 60 ? seconds / 60 + "분" : seconds + "초";
	}

	private String chunking() {
		return properties.chunk().settings().fingerprint();
	}
}
