package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakEventsConfig;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakGroup;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakRole;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakUser;
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
 *               (1) 이벤트 설정 읽기(감사를 끈 변화 감지) (2) realm 역할 id→이름 표 읽기 (3) 기준선이 없으면 한 번 잡기
 *               (4) 커서 이후 이벤트 수집 (5) 상태 기록 순이다.
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
	/**
	 * 권한 구성을 바꾸거나 다른 사람으로 행동하거나 이벤트를 지울 수 있는 realm-management 역할. 기준선에 보유자를 남긴다.
	 * 조회 전용(view-·query-) 역할은 넣지 않는다 — 수집기 서비스 계정 자신도 그것을 가진다.
	 */
	static final List<String> MANAGEMENT_ROLES = List.of("realm-admin", "manage-users", "manage-realm", "manage-events",
			"manage-clients", "manage-authorization", "manage-identity-providers", "impersonation", "create-client");

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
			RoleNames roleNames = roleNames();
			if (!store.baselineRecorded()) {
				List<KeycloakAuditRow> baseline = baseline(now, realmManagement);
				transactions.executeWithoutResult(status -> store.insert(baseline));
			}
			collectEvents(realmManagement, roleNames);
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

	/**
	 * roles-by-id 경로의 삭제·이름 변경 이벤트에는 역할 이름이 없다. 그래서 realm 역할 id→이름 표를 저장해 두고 쓴다.
	 * 조회용 표는 <b>지난 주기에 저장한 이름에서 시작해</b> 이번 주기의 이벤트를 순서대로 반영한다(생성은 상세의 id로, 이름 변경은
	 * 새 이름으로) — 지금 Keycloak의 이름으로 시작하면 이번 주기에 이름이 바뀐 역할의 "바뀌기 전" 이름을 잃는다.
	 * 저장은 지난 표·이번 주기에 배운 이름 위에 지금 Keycloak의 이름을 덮고, 지워진 역할의 이름도 남긴다.
	 */
	private record RoleNames(Map<String, String> lookup, Map<String, String> current, Map<String, String> previous) {

		/** 분류한 행에서 생성·이름 변경을 읽어 표를 고친다. */
		void learn(KeycloakAuditRow row) {
			if (row.kind() != KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED) return;
			Object operation = row.reference().get("operation");
			Object roleId = row.reference().get("roleId");
			if ("CREATE".equals(operation) && roleId != null && row.role() != null) lookup.put(String.valueOf(roleId), row.role());
			if ("RENAME".equals(operation) && row.role() != null) {
				String id = roleId != null ? String.valueOf(roleId) : idOf(String.valueOf(row.reference().get("before")));
				if (id != null) lookup.put(id, row.role());
			}
		}

		private String idOf(String name) {
			return lookup.entrySet().stream().filter(entry -> entry.getValue().equals(name)).map(Map.Entry::getKey)
					.findFirst().orElse(null);
		}

		Map<String, String> toSave() {
			Map<String, String> saved = new HashMap<>(previous);
			saved.putAll(lookup);
			saved.putAll(current);
			return saved;
		}
	}

	private RoleNames roleNames() {
		Map<String, String> previous = store.roleNames();
		Map<String, String> current = new HashMap<>();
		for (KeycloakRole role : block(keycloak.realmRoles())) current.put(role.id(), role.name());
		Map<String, String> lookup = new HashMap<>(current);
		lookup.putAll(previous);
		return new RoleNames(lookup, current, previous);
	}

	private void collectEvents(String realmManagement, RoleNames roleNames) {
		Optional<Instant> cursor = store.cursor();
		// Keycloak의 기간 필터는 날짜 단위다. 시간대 차이를 넘기려고 하루 앞부터 읽는다.
		LocalDate from = cursor.map(at -> at.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)).orElse(null);
		List<KeycloakAdminEvent> events = new ArrayList<>(block(keycloak.adminEvents(from)));
		events.sort(Comparator.comparingLong(KeycloakAdminEvent::time).thenComparing(KeycloakAuditClassifier::eventId));
		Instant floor = cursor.map(at -> at.minus(REREAD_WINDOW)).orElse(Instant.MIN);
		Map<String, Boolean> enabledInBatch = new HashMap<>();
		KeycloakAuditClassifier.Context context = new KeycloakAuditClassifier.Context(realmManagement,
				subject -> enabledInBatch.containsKey(subject) ? Optional.of(enabledInBatch.get(subject))
						: store.lastKnownEnabled(subject),
				id -> Optional.ofNullable(roleNames.lookup().get(id)));
		List<KeycloakAuditRow> rows = new ArrayList<>();
		KeycloakAdminEvent last = null;
		for (KeycloakAdminEvent event : events) {
			if (Instant.ofEpochMilli(event.time()).isBefore(floor)) continue;
			for (KeycloakAuditRow row : classifier.classify(event, context)) {
				if (row.kind() == KeycloakAuditEventKind.USER_ENABLED) enabledInBatch.put(row.targetSubject(), true);
				if (row.kind() == KeycloakAuditEventKind.USER_DISABLED) enabledInBatch.put(row.targetSubject(), false);
				roleNames.learn(row);
				rows.add(row);
			}
			last = event;
		}
		KeycloakAdminEvent newest = last;
		transactions.executeWithoutResult(status -> {
			store.insert(rows);
			store.recordRoleNames(roleNames.toSave());
			if (newest != null) {
				store.advanceCursor(Instant.ofEpochMilli(newest.time()), KeycloakAuditClassifier.eventId(newest));
			}
		});
	}

	/**
	 * 수집을 처음 켤 때의 보유 상태.
	 * (1) 경로별: PLATFORM_ADMIN의 직접·그룹(하위 그룹 포함)·realm 복합 역할 경유 보유자, 관리 역할의 직접·그룹 보유자를 경로마다 한 행.
	 * (2) 실효 보강: 모든 계정의 실효 역할을 읽어, (1)에서 빠진 보유자(기본 역할·클라이언트 복합 역할 경유 등)를 via=effective로 남긴다.
	 * (3) 이미 비활성인 계정은 USER_DISABLED로 남긴다 — 나중에 켜는 것이 권한 획득이라 비교 기준이 필요하다.
	 * 마지막에 BASELINE_RECORDED 표지를 붙인다. PLATFORM_ADMIN 역할이 아직 없는 realm도 보유자 0명으로 기준선을 잡는다.
	 */
	private List<KeycloakAuditRow> baseline(Instant now, String realmManagement) {
		List<KeycloakAuditRow> rows = new ArrayList<>();
		holders(now, PLATFORM_ADMIN, orNone(keycloak.realmRoleUserIds(PLATFORM_ADMIN)),
				orNone(keycloak.realmRoleGroups(PLATFORM_ADMIN)), "", Map.of(), rows);
		for (String composite : rolesContaining(PLATFORM_ADMIN)) {
			holders(now, PLATFORM_ADMIN, block(keycloak.realmRoleUserIds(composite)),
					block(keycloak.realmRoleGroups(composite)), "roles/" + composite + "/", Map.of("compositeRole", composite),
					rows);
		}
		for (String role : MANAGEMENT_ROLES) {
			holders(now, KeycloakAuditClassifier.REALM_MANAGEMENT + "/" + role,
					orNone(keycloak.clientRoleUserIds(realmManagement, role)),
					orNone(keycloak.clientRoleGroups(realmManagement, role)), "", Map.of(), rows);
		}
		Set<String> covered = new HashSet<>();
		for (KeycloakAuditRow row : rows) covered.add(row.targetSubject() + "|" + row.role());
		for (KeycloakUser user : block(keycloak.users())) {
			List<String> held = new ArrayList<>();
			if (block(keycloak.effectiveRealmRoleNames(user.id())).contains(PLATFORM_ADMIN)) held.add(PLATFORM_ADMIN);
			for (String role : block(keycloak.effectiveClientRoleNames(user.id(), realmManagement))) {
				if (MANAGEMENT_ROLES.contains(role)) held.add(KeycloakAuditClassifier.REALM_MANAGEMENT + "/" + role);
			}
			for (String role : held) {
				if (covered.add(user.id() + "|" + role)) {
					rows.add(held(now, user.id(), role, "users/" + user.id() + "/effective", Map.of("via", "effective"), null));
				}
			}
			if (Boolean.FALSE.equals(user.enabled())) {
				rows.add(new KeycloakAuditRow(null, KeycloakAuditEventKind.USER_DISABLED, now, null,
						Map.of("source", "baseline"), user.id(), null, "users/" + user.id(), Map.of("source", "baseline")));
			}
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("holders", rows.stream().filter(row -> row.kind() == KeycloakAuditEventKind.ROLE_HELD_AT_START).count());
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
		if (!reference.containsKey("via")) {
			reference.put("via", group == null ? (via.isEmpty() ? "direct" : "composite")
					: (via.isEmpty() ? "group" : "composite-group"));
		}
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

	/** target을 직간접으로 품은 realm 복합 역할 이름. 클라이언트 복합 경유는 실효 보강(baseline (2))이 잡는다. */
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

	/** 역할이 아직 없으면(404) 보유자가 없는 것이다. 역할이 없다고 수집 전체를 멈추지 않는다. */
	private static <T> List<T> orNone(Mono<List<T>> call) {
		return block(call.onErrorResume(WebClientResponseException.NotFound.class, missing -> Mono.just(List.of())));
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
