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
	/** 이벤트 목록 전체 읽기의 상한. 오래 멈췄다 다시 도는 첫 주기는 며칠치를 읽을 수 있다. */
	private static final Duration EVENTS_TIMEOUT = Duration.ofMinutes(5);
	/**
	 * 커서보다 이만큼 앞의 이벤트까지 다시 분류한다(중복은 멱등 키가 막는다). Keycloak은 이벤트 시각을 요청 시작에 찍고 커밋 뒤에야
	 * 보이므로, 긴 트랜잭션(대량 import 등, Keycloak 기본 트랜잭션 제한 5분)의 이벤트가 더 늦은 이벤트보다 나중에 보일 수 있다.
	 */
	private static final Duration REREAD_WINDOW = Duration.ofMinutes(10);
	/** 커서가 이보다 더 미래면 수집을 실패로 본다(시계 차이는 이 정도까지 허용). */
	private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

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
			String realmManagement = block(keycloak.realmManagementUuid())
					.orElseThrow(() -> new CollectorProblem(
							"realm-management 클라이언트를 찾을 수 없다(서비스 계정의 view-users 권한과 realm-management 역할 확인)"));
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
			// Keycloak이 재시작 등으로 토큰을 먼저 무효화했으면 다음 주기에 새 토큰을 받는다.
			if (failure instanceof WebClientResponseException.Unauthorized) keycloak.invalidateToken();
			String summary = summary(failure);
			boolean changed = !summary.equals(lastFailure);
			try {
				store.recordFailure(now, summary);
			} catch (RuntimeException statusFailure) {
				if (changed) log.warn("Keycloak 권한 변경 감사 수집 상태를 기록하지 못했다: {}", summary(statusFailure));
			}
			if (changed) log.warn("Keycloak 권한 변경 감사 수집 실패: {}", summary);
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

		void rewind(List<KeycloakAuditStore.RoleRename> renames) {
			for (KeycloakAuditStore.RoleRename rename : renames) {
				if (rename.before() == null) continue;
				String id = rename.roleId() != null ? rename.roleId() : idOf(rename.after());
				if (id != null) lookup.put(id, rename.before());
			}
		}

		void replayBefore(KeycloakAdminEvent event, Deque<KeycloakAuditStore.RoleRename> renames) {
			Instant at = Instant.ofEpochMilli(event.time());
			String eventId = KeycloakAuditClassifier.eventId(event);
			while (!renames.isEmpty()) {
				KeycloakAuditStore.RoleRename rename = renames.peek();
				if (rename.at().isAfter(at) || (rename.at().equals(at) && rename.eventId().compareTo(eventId) >= 0)) break;
				renames.remove();
				String id = rename.roleId() != null ? rename.roleId() : idOf(rename.before());
				if (id != null && rename.after() != null) lookup.put(id, rename.after());
			}
		}

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
		// 커서가 미래면 그보다 앞선 실제 이벤트가 모두 "이미 처리함"으로 버려져 감사가 조용히 빈다(상태 행 조작이나 Keycloak 시계
		// 오류). 정상처럼 보이지 않게 실패로 드러낸다.
		if (cursor.isPresent() && cursor.get().isAfter(Instant.now().plus(FUTURE_TOLERANCE))) {
			throw new CollectorProblem("수집 커서가 현재 시각보다 미래다(keycloak_adt_crs.evt_at 또는 Keycloak 서버 시계 확인)");
		}
		// Keycloak의 기간 필터는 날짜 단위다. 시간대 차이를 넘기려고 하루 앞부터 읽는다.
		LocalDate from = cursor.map(at -> at.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)).orElse(null);
		List<KeycloakAdminEvent> events = new ArrayList<>(keycloak.adminEvents(from).block(EVENTS_TIMEOUT));
		events.sort(Comparator.comparingLong(KeycloakAdminEvent::time).thenComparing(KeycloakAuditClassifier::eventId));
		Instant floor = cursor.map(at -> at.minus(REREAD_WINDOW)).orElse(Instant.MIN);
		// 지난 주기의 최종 이름으로 재독하면 이름 변경 전 이벤트가 다른 role로 다시 저장된다.
		// 지연 도착한 이벤트도 당시 이름을 사용하도록, 저장된 변경을 역순으로 되돌린 뒤 정순 재생한다.
		List<KeycloakAuditStore.RoleRename> renames = cursor.isPresent() ? store.roleRenamesSince(floor) : List.of();
		roleNames.rewind(renames);
		Deque<KeycloakAuditStore.RoleRename> renameReplay = new ArrayDeque<>();
		renames.forEach(renameReplay::addFirst);
		// 이번 배치에서 먼저 나온 활성 상태 변화. 시각 순으로 처리하므로 조회 시점보다 앞선 것만 쓴다.
		Map<String, List<KeycloakAuditStore.EnabledAt>> enabledInBatch = new HashMap<>();
		KeycloakAuditClassifier.Context context = new KeycloakAuditClassifier.Context(realmManagement,
				(subject, at) -> enabledBefore(subject, at, enabledInBatch),
				id -> Optional.ofNullable(roleNames.lookup().get(id)));
		List<KeycloakAuditRow> rows = new ArrayList<>();
		KeycloakAdminEvent last = null;
		for (KeycloakAdminEvent event : events) {
			if (Instant.ofEpochMilli(event.time()).isBefore(floor)) continue;
			// Keycloak에서 이미 지운 변경도 DB 사본으로 재생해 지연 이벤트의 당시 이름을 유지한다.
			roleNames.replayBefore(event, renameReplay);
			List<KeycloakAuditRow> classified;
			try {
				classified = classifier.classify(event, context);
			} catch (RuntimeException unreadable) {
				// 이벤트 한 건 때문에 수집 전체가 멈추면 이후 감사가 모두 끊긴다. 요지만 남기고 넘어간다.
				classified = List.of(KeycloakAuditClassifier.unreadable(event, unreadable));
			}
			for (KeycloakAuditRow row : classified) {
				if (row.kind() == KeycloakAuditEventKind.USER_ENABLED || row.kind() == KeycloakAuditEventKind.USER_DISABLED) {
					enabledInBatch.computeIfAbsent(row.targetSubject(), ignored -> new ArrayList<>()).add(
							new KeycloakAuditStore.EnabledAt(row.kind() == KeycloakAuditEventKind.USER_ENABLED, row.occurredAt()));
				}
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

	/** 주어진 시각 이전의 마지막 활성 상태 — DB 기록과 이번 배치에서 먼저 나온 변화 중 더 늦은 것. */
	private Optional<Boolean> enabledBefore(String subject, Instant at,
			Map<String, List<KeycloakAuditStore.EnabledAt>> enabledInBatch) {
		Optional<KeycloakAuditStore.EnabledAt> latest = store.enabledBefore(subject, at);
		for (KeycloakAuditStore.EnabledAt change : enabledInBatch.getOrDefault(subject, List.of())) {
			if (change.at().isBefore(at) && (latest.isEmpty() || !change.at().isBefore(latest.get().at()))) {
				latest = Optional.of(change);
			}
		}
		return latest.map(KeycloakAuditStore.EnabledAt::enabled);
	}

	/**
	 * 수집을 처음 켤 때의 보유 상태.
	 * (1) 경로별: PLATFORM_ADMIN의 직접·그룹(하위 그룹 포함)·realm 복합 역할 경유 보유자를 경로마다 한 행.
	 * (2) 실효 보강: 모든 계정의 실효 역할을 읽어, (1)에서 빠진 PLATFORM_ADMIN 보유자(클라이언트 복합 역할 경유 등)와 관리 역할
	 *     보유자를 남긴다. 관리 역할은 직접 붙은 것만 direct, 나머지(그룹·복합)는 effective다.
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
		Set<String> covered = new HashSet<>();
		for (KeycloakAuditRow row : rows) covered.add(row.targetSubject() + "|" + row.role());
		for (KeycloakUser user : block(keycloak.users())) {
			List<String> held = new ArrayList<>();
			// 기준선을 잡는 사이 지워진 계정(404)은 건너뛴다 — 한 명 때문에 기준선 전체를 버리지 않는다.
			if (orNone(keycloak.effectiveRealmRoleNames(user.id())).contains(PLATFORM_ADMIN)) held.add(PLATFORM_ADMIN);
			List<String> management = orNone(keycloak.effectiveClientRoleNames(user.id(), realmManagement)).stream()
					.filter(MANAGEMENT_ROLES::contains).toList();
			for (String role : management) held.add(KeycloakAuditClassifier.REALM_MANAGEMENT + "/" + role);
			// 관리 역할은 클라이언트 역할 보유자 조회(view-clients)를 쓰지 않고 실효 역할로 판정한다. 직접 붙은 것만 direct로 가른다.
			List<String> direct = management.isEmpty() ? List.of()
					: orNone(keycloak.directClientRoleNames(user.id(), realmManagement)).stream()
							.map(role -> KeycloakAuditClassifier.REALM_MANAGEMENT + "/" + role).toList();
			for (String role : held) {
				if (!covered.add(user.id() + "|" + role)) continue;
				boolean directly = direct.contains(role);
				rows.add(held(now, user.id(), role, "users/" + user.id() + (directly ? "" : "/effective"),
						Map.of("via", directly ? "direct" : "effective"), null));
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
			members.addAll(orNone(keycloak.groupMemberIds(current)));
			for (KeycloakGroup child : orNone(keycloak.subGroups(current))) pending.push(child.id());
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

	/** 역할·계정·그룹이 없으면(404) 보유자가 없는 것이다. 없다고 수집 전체를 멈추지 않는다. */
	private static <T> List<T> orNone(Mono<List<T>> call) {
		return block(call.onErrorResume(WebClientResponseException.NotFound.class, missing -> Mono.just(List.of())));
	}

	private static <T> T block(Mono<T> call) {
		return call.block(CALL_TIMEOUT);
	}

	/**
	 * 수집 상태에 남길 요지. PLATFORM_ADMIN에게 조회로 보이므로 응답 본문·토큰·SQL(행 값이 들어 있다)·내부 호스트는 넣지 않는다.
	 * Keycloak 응답은 상태와 경로만, 이 클래스가 직접 만든 설명(CollectorProblem)은 문장 그대로, 나머지는 예외 종류만 남긴다.
	 */
	static String summary(RuntimeException failure) {
		if (failure instanceof WebClientResponseException response) {
			String path = response.getRequest() == null ? "" : " " + response.getRequest().getURI().getPath();
			return "Keycloak " + response.getStatusCode().value() + path;
		}
		if (failure instanceof CollectorProblem) return failure.getMessage();
		return failure.getClass().getSimpleName();
	}

	/** 수집기가 직접 설명하는 문제. 문장에 비밀·행 값을 넣지 않으므로 그대로 수집 상태에 남긴다. */
	static final class CollectorProblem extends IllegalStateException {
		CollectorProblem(String message) {
			super(message);
		}
	}
}
