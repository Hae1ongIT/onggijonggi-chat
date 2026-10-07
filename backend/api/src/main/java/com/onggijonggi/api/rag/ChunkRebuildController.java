package com.onggijonggi.api.rag;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ChunkRebuildController.java
 * Description : 방 문서 검색 조각의 일괄 재처리(#348). PLATFORM_ADMIN 전용 — 보안 설정이 `/api/platform/**`를 막는다.
 *               요청은 남기기만 하고 ETL이 다음 주기(기본 30초)에 대상을 골라 처리한다. 처리하는 동안 문서는 이전 조각으로 계속 검색된다.
 *               - POST `{"scope":"ALL"|"OUTDATED"}` → 202. 진행 중인 요청이 있으면 409(REBUILD_IN_PROGRESS).
 *               - GET 목록(최근 10건)·한 건 → 대상·남은·실패 수.
 */
@RestController
public class ChunkRebuildController {

	/** scope: ALL(READY 전체) · OUTDATED(임베딩 모델·차원·청킹 설정이 지금 ETL 설정과 다른 문서). */
	public record RebuildRequest(String scope) { }

	private final CurrentActorProvider actors;
	private final ChunkRebuildService service;

	public ChunkRebuildController(CurrentActorProvider actors, ChunkRebuildService service) {
		this.actors = actors;
		this.service = service;
	}

	@PostMapping("/api/platform/rag/rebuilds")
	public Mono<ResponseEntity<ChunkRebuildService.Rebuild>> request(@RequestBody(required = false) RebuildRequest body) {
		ChunkRebuildService.Scope scope = scope(body);
		return actors.currentActor()
				.flatMap(actor -> Mono.fromCallable(() -> service.request(scope, actor.subject())).subscribeOn(Schedulers.boundedElastic()))
				.map(rebuild -> ResponseEntity.status(HttpStatus.ACCEPTED).body(rebuild));
	}

	@GetMapping("/api/platform/rag/rebuilds")
	public Mono<List<ChunkRebuildService.Rebuild>> recent() {
		return Mono.fromCallable(service::recent).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping("/api/platform/rag/rebuilds/{id}")
	public Mono<ChunkRebuildService.Rebuild> find(@PathVariable UUID id) {
		return Mono.fromCallable(() -> service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private static ChunkRebuildService.Scope scope(RebuildRequest body) {
		try {
			return ChunkRebuildService.Scope.valueOf(body == null || body.scope() == null ? "" : body.scope());
		} catch (IllegalArgumentException invalid) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope는 ALL 또는 OUTDATED");
		}
	}
}
