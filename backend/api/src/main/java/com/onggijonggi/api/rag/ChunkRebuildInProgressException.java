package com.onggijonggi.api.rag;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ChunkRebuildInProgressException.java
 * Description : 진행 중인 일괄 재처리 요청이 있어 새 요청을 받지 않는다(#348). 같은 문서를 두 번 다시 만들지 않기 위해서다.
 *               운영자가 진행 상황을 볼 수 있게 진행 중인 요청 ID를 문구에 담는다 — 운영자 전용 API라 화면 문구 규칙과 무관하다.
 */
public class ChunkRebuildInProgressException extends ResponseStatusException {

	private final UUID running;

	public ChunkRebuildInProgressException(UUID running) {
		super(HttpStatus.CONFLICT, "진행 중인 일괄 재처리 요청이 있다: " + running);
		this.running = running;
	}

	public UUID running() {
		return running;
	}
}
