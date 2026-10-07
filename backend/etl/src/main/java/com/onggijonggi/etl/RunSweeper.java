package com.onggijonggi.etl;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : RunSweeper.java
 * Description : 회차 없는 등록 문서에 회차를 보충하고(RunStore.requeueOrphans), 더 쓰지 않는 회차의 청크를 Elasticsearch에서 지운다 — 실패·취소된 회차, 삭제된 문서·방의 회차, 재처리로 대체된
 *               회차. 지운 회차는 PURGED로 표시한다. A의 원본 정리 큐(thr_doc_end)와 따로 돌아, ETL을 띄우지 않은 배포에서도
 *               원본 정리와 재등록이 막히지 않는다. 지우기는 멱등이라 실패해도 다음 주기에 다시 한다.
 */
@Component
public class RunSweeper {

	private static final Logger log = LoggerFactory.getLogger(RunSweeper.class);

	/** 한 주기에 다루는 회차·문서 수. 주기(app.etl.sweep-delay, 기본 30초)마다 이만큼씩 줄여 간다. */
	private static final int BATCH = 20;

	private final RunStore runs;
	private final ChunkIndex index;

	public RunSweeper(RunStore runs, ChunkIndex index) {
		this.runs = runs;
		this.index = index;
	}

	@Scheduled(fixedDelayString = "${app.etl.sweep-delay:30s}")
	public void sweep() {
		try {
			int queued = runs.requeueOrphans(BATCH);
			if (queued > 0) log.warn("처리 회차가 없던 등록 문서 {}건에 회차를 보충했다", queued);
		} catch (RuntimeException error) {
			log.warn("회차 없는 등록 문서를 보충하지 못했다 — 다음 주기에 다시 본다", error);
		}
		if (index.migrating()) {
			// 이전이 끝나면 새 인덱스에서 지운다(ChunkIndex.delete). 기다리는 동안 행마다 실패 로그를 남기지 않는다.
			log.info("검색 인덱스 이전이 끝나지 않아 지난 회차 청크 정리를 미룬다");
			return;
		}
		List<RunStore.Stale> due;
		try {
			due = runs.stale(BATCH);
		} catch (RuntimeException error) {
			log.warn("정리할 회차를 읽지 못했다 — 다음 주기에 다시 본다", error);
			return;
		}
		// 행마다 따로 처리한다. 한 행이 계속 실패해도 뒤의 정리 대상이 막히지 않는다.
		for (RunStore.Stale stale : due) {
			try {
				index.delete(stale.document(), stale.runSeq());
				runs.purged(stale);
			} catch (RuntimeException error) {
				log.warn("지난 회차 청크 정리 실패 — 다음 주기에 다시 한다: doc={} run={}", stale.document(), stale.runSeq(), error);
				try {
					runs.postpone(stale);
				} catch (RuntimeException ignored) {
					// 미루지 못해도 다음 주기에 같은 행을 다시 본다.
				}
			}
		}
	}
}
