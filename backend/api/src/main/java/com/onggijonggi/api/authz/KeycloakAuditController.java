package com.onggijonggi.api.authz;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Class Name : KeycloakAuditController.java
 * Description : Keycloak 역할·계정 변경 감사 조회(#304). PLATFORM_ADMIN 전용 — 보안 설정이 `/api/platform/**`를 막는다.
 *               감사 대상이 PLATFORM_ADMIN 자신이지만 기록을 고치거나 지울 수 없어 무결성에는 영향이 없다.
 *               필터는 문자열로 받아 KeycloakAuditQuery가 해석한다 — 틀린 값을 모두 같은 400 봉투로 돌리기 위해서다.
 */
@RestController
public class KeycloakAuditController {

	private final KeycloakAuditQueryService queryService;

	public KeycloakAuditController(KeycloakAuditQueryService queryService) {
		this.queryService = queryService;
	}

	@GetMapping("/api/platform/rbac/keycloak-audits")
	public Mono<KeycloakAuditPage> audits(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String eventKind,
			@RequestParam(required = false) String targetSubject, @RequestParam(required = false) String actorSubject,
			@RequestParam(required = false) String role, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor) {
		return queryService.page(KeycloakAuditQuery.parse(from, to, eventKind, targetSubject, actorSubject, role, limit, cursor));
	}
}
