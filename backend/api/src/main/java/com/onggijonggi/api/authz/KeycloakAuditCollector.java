package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakEventsConfig;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakGroup;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakRole;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Class Name : KeycloakAuditCollector.java
 * Description : Keycloak admin event를 주기적으로 읽어 keycloak_adt에 옮긴다(#304). Keycloak 쪽 기록은 realm 관리자가
 *               지울 수 있어, 지우기 전에 append-only 사본을 남기는 것이 목적이다. 한 주기는
 *               (1) 이벤트 설정 읽기(감사를 끈 변화 감지) (2) 기준선이 없으면 한 번 잡기 (3) 커서 이후 이벤트 수집 (4) 상태 기록 순이다.
 *               실패는 수집 상태에 남기고 다음 주기에 다시 시도한다 — 같은 원인이 반복되면 로그는 처음 한 번만 남긴다
 *               (권한을 아직 안 준 환경이 매분 로그를 쌓지 않게). 채팅·권한 판정에는 영향을 주지 않는다.
 *               모든 배포에서 기본으로 켜고, Keycloak Admin 설정이 없는 테스트 등에서만 app.keycloak.audit.enabled=false로 끈다.
 */
@Component
@ConditionalOnProperty(name = "app.keycloak.audit.enabled", havingValue = "true", matchIfMissing = true)
public class KeycloakAuditCollector {

	private static final Logger log = LoggerFactory.getLogger(KeycloakAuditCollector.class);
	private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
	/** 날짜 단위로 다시 읽은 이벤트 중 이만큼 이전 것은 이미 처리한 것으로 보고 건너뛴다(중복은 어차피 멱등 키가 막는다). */
	private static final Duration REREAD_WINDOW = Duration.ofMinutes(1);

	static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
	/** 역할을 줄 수 있거나 이벤트를 지울 수 있는 realm-management 관리 역할. 기준선에 보유자를 남긴다. */
	static final List<String> MANAGEMENT_ROLES = List.of("realm-admin", "manage-users", "manage-realm", "manage-events");

	private final KeycloakAdminClient keycloak;
	private final KeycloakAuditClassifier classifier;
	private final KeycloakAuditStore store;
	private final TransactionTemplate transactions;
	private volatile String lastFailure;

	public KeycloakAuditCollector(KeycloakAdminClient keycloak, KeycloakAuditClassifier classifier, KeycloakAuditStore store,
			PlatformTransactionManager transactionManager) {
		this.keycloak = keycloak;
		this.classifier = classifier;
		this.store = store;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Scheduled(fixedDelayString = "${app.keycloak.audit.interval:1m}", initialDelayString = "${app.keycloak.audit.initial-delay:20s}")
	public void scheduledRun() {
		runOnce();
	}

	/** 한 주기. 예외를 밖으로 던지지 않는다 — 실패는 수집 상태에 남는다. */
	public void runOnce() {
		Instant now = Instant.now();
		try {
			recordEventsConfig(now, block(keycloak.eventsConfig()));
			// 권한이 없으면 403이 아니라 빈 목록이 온다. 원인을 바로 알 수 있게 필요한 권한을 적는다.
			String realmManagement = block(keycloak.clientUuid(KeycloakAuditClassifier.REALM_MANAGEMENT))
					.orElseThrow(() -> new IllegalStateException(
							"realm-management 클라이언트를 찾을 수 없다(서비스 계정의 view-clients 권한 확인)"));
			if (!store.baselineRecorded()) {
				List<KeycloakAuditRow> baseline = baseline(now, realmManagement);
				transactions.executeWithoutResult(status -> store.insert(baseline));
			}
			collectEvents(realmManagement);
			store.recordSuccess(now);
			if (lastFailure != null) log.info("Keycloak 권한 변경 감사 수집이 회복됐다");
			lastFailure = null;
		} catch (RuntimeException failure) {
			String summary = summary(failure);
			try {
				store.recordFailure(now, summary);
			} catch (RuntimeException statusFailure) {
				log.warn("Keycloak 권한 변경 감사 수집 상태를 기록하지 못했다", statusFailure);
			}
			if (!summary.equals(lastFailure)) log.warn("Keycloak 권한 변경 감사 수집 실패: {}", summary);
			lastFailure = summary;
		}
	}

	/** 감사 설정이 바뀌었으면(특히 admin event 끄기) Keycloak 이벤트와 별도로 수집기가 본 변화를 남긴다. */
	private void recordEventsConfig(Instant now, KeycloakEventsConfig config) {
		Map<String, Object> current = new LinkedHashMap<>();
		current.put("adminEventsEnabled", Boolean.TRUE.equals(config.adminEventsEnabled()));
		current.put("adminEventsDetailsEnabled", Boolean.TRUE.equals(config.adminEventsDetailsEnabled()));
		Optional<Map<String, Object>> previous = store.lastEventsConfig();
		transactions.executeWithoutResult(status -> {
			if (previous.isPresent() && !previous.get().equals(current)) {
				Map<String, Object> reference = new LinkedHashMap<>();
				reference.put("before", previous.get());
				reference.put("after", current);
				store.insert(List.of(new KeycloakAuditRow("collector:" + now.toEpochMilli(),
						KeycloakAuditEventKind.EVENT_CONFIG_CHANGED, now, null, Map.of("source", "collector"), null, null,
						"events/config", reference)));
			}
			store.recordEventsConfig(current);
		});
	}

	private void collectEvents(String realmManagement) {
		Optional<Instant> cursor = store.cursor();
		// Keycloak의 기간 필터는 날짜 단위다. 시간대 차이를 넘기려고 하루 앞부터 읽는다.
		LocalDate from = cursor.map(at -> at.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)).orElse(null);
		List<KeycloakAdminEvent> events = new ArrayList<>(block(keycloak.adminEvents(from)));
		events.sort(Comparator.comparingLong(KeycloakAdminEvent::time).thenComparing(KeycloakAuditClassifier::eventId));
		Instant floor = cursor.map(at -> at.minus(REREAD_WINDOW)).orElse(Instant.MIN);
		Map<String, Boolean> enabledInBatch = new HashMap<>();
		List<KeycloakAuditRow> rows = new ArrayList<>();
		KeycloakAdminEvent last = null;
		for (KeycloakAdminEvent event : events) {
			if (Instant.ofEpochMilli(event.time()).isBefore(floor)) continue;
			for (KeycloakAuditRow row : classifier.classify(event, realmManagement,
					subject -> enabledInBatch.containsKey(subject) ? Optional.of(enabledInBatch.get(subject))
							: store.lastKnownEnabled(subject))) {
				if (row.kind() == KeycloakAuditEventKind.USER_ENABLED) enabledInBatch.put(row.targetSubject(), true);
				if (row.kind() == KeycloakAuditEventKind.USER_DISABLED) enabledInBatch.put(row.targetSubject(), false);
				rows.add(row);
			}
			last = event;
		}
		KeycloakAdminEvent newest = last;
		transactions.executeWithoutResult(status -> {
			store.insert(rows);
			if (newest != null) {
				store.advanceCursor(Instant.ofEpochMilli(newest.time()), KeycloakAuditClassifier.eventId(newest));
			}
		});
	}

	/**
	 * 수집을 처음 켤 때의 보유 상태. PLATFORM_ADMIN은 직접·그룹(하위 그룹 포함)·복합 역할 경유 보유자를, realm-management
	 * 관리 역할은 직접·그룹 보유자를 남긴다. 사람과 보유 경로마다 한 행이고 마지막에 BASELINE_RECORDED 표지를 붙인다.
	 */
	private List<KeycloakAuditRow> baseline(Instant now, String realmManagement) {
		List<KeycloakAuditRow> rows = new ArrayList<>();
		holders(now, PLATFORM_ADMIN, block(keycloak.realmRoleUserIds(PLATFORM_ADMIN)),
				block(keycloak.realmRoleGroups(PLATFORM_ADMIN)), "", Map.of(), rows);
		for (String composite : rolesContaining(PLATFORM_ADMIN)) {
			holders(now, PLATFORM_ADMIN, block(keycloak.realmRoleUserIds(composite)),
					block(keycloak.realmRoleGroups(composite)), "roles/" + composite + "/", Map.of("compositeRole", composite),
					rows);
		}
		for (String role : MANAGEMENT_ROLES) {
			holders(now, KeycloakAuditClassifier.REALM_MANAGEMENT + "/" + role,
					block(keycloak.clientRoleUserIds(realmManagement, role)),
					block(keycloak.clientRoleGroups(realmManagement, role)), "", Map.of(), rows);
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("holders", rows.size());
		rows.add(new KeycloakAuditRow("baseline", KeycloakAuditEventKind.BASELINE_RECORDED, now, null,
				Map.of("source", "baseline"), null, null, "baseline", summary));
		return rows;
	}

	private void holders(Instant now, String role, List<String> users, List<KeycloakGroup> groups, String pathPrefix,
			Map<String, Object> via, List<KeycloakAuditRow> rows) {
		for (String user : users) {
			rows.add(held(now, user, role, pathPrefix + "users/" + user, via, null));
		}
		for (KeycloakGroup group : groups) {
			for (String member : groupMembers(group.id())) {
				rows.add(held(now, member, role, pathPrefix + "groups/" + group.id(), via, group));
			}
		}
	}

	private static KeycloakAuditRow held(Instant now, String subject, String role, String path, Map<String, Object> via,
			KeycloakGroup group) {
		Map<String, Object> reference = new LinkedHashMap<>(via);
		reference.put("via", group == null ? (via.isEmpty() ? "direct" : "composite") : (via.isEmpty() ? "group" : "composite-group"));
		if (group != null) {
			reference.put("groupId", group.id());
			if (group.path() != null) reference.put("groupPath", group.path());
		}
		return new KeycloakAuditRow(null, KeycloakAuditEventKind.ROLE_HELD_AT_START, now, null, Map.of("source", "baseline"),
				subject, role, path, reference);
	}

	/** 그룹과 하위 그룹의 구성원. 하위 그룹은 상위 그룹의 역할을 물려받는다. */
	private Set<String> groupMembers(String groupId) {
		Set<String> members = new HashSet<>();
		Deque<String> pending = new ArrayDeque<>(List.of(groupId));
		Set<String> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			String current = pending.pop();
			if (!visited.add(current)) continue;
			members.addAll(block(keycloak.groupMemberIds(current)));
			for (KeycloakGroup child : block(keycloak.subGroups(current))) pending.push(child.id());
		}
		return members;
	}

	/** target을 직간접으로 품은 realm 복합 역할 이름. */
	private Set<String> rolesContaining(String target) {
		Map<String, Set<String>> parentsOf = new HashMap<>();
		for (KeycloakRole role : block(keycloak.realmRoles())) {
			if (!Boolean.TRUE.equals(role.composite())) continue;
			for (KeycloakRole child : block(keycloak.composites(role.name()))) {
				if (Boolean.TRUE.equals(child.clientRole())) continue;
				parentsOf.computeIfAbsent(child.name(), ignored -> new HashSet<>()).add(role.name());
			}
		}
		Set<String> result = new HashSet<>();
		Deque<String> pending = new ArrayDeque<>(List.of(target));
		while (!pending.isEmpty()) {
			for (String parent : parentsOf.getOrDefault(pending.pop(), Set.of())) {
				if (result.add(parent)) pending.push(parent);
			}
		}
		return result;
	}

	private static <T> T block(Mono<T> call) {
		return call.block(CALL_TIMEOUT);
	}

	/** 수집 상태에 남길 요지. 응답 본문·토큰은 넣지 않는다. */
	static String summary(RuntimeException failure) {
		if (failure instanceof WebClientResponseException response) {
			String path = response.getRequest() == null ? "" : " " + response.getRequest().getURI().getPath();
			return "Keycloak " + response.getStatusCode().value() + path;
		}
		String message = failure.getMessage() == null ? "" : ": " + failure.getMessage();
		String text = failure.getClass().getSimpleName() + message;
		return text.length() > 300 ? text.substring(0, 300) : text;
	}
}
