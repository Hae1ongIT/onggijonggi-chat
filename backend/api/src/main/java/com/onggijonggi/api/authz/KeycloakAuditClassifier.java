package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : KeycloakAuditClassifier.java
 * Description : Keycloak admin event 한 건을 keycloak_adt 행으로 바꾼다(#304). 권한과 관련된 것만 남기고 나머지는 빈 목록이다.
 *               종류는 resourceType이 아니라 리소스 경로 모양과 작업 종류로 가른다 — 경로가 Admin API 주소 그 자체라 더 안정적이다.
 *               상세(representation) 원문은 저장하지 않고 역할 이름·그룹 경로·enabled 같은 필요한 값만 뽑는다.
 *               상세가 꺼져 있어 역할 이름을 모르면 역할 없이 한 행을 남기고 reference에 detailsMissing을 적는다.
 */
@Component
public class KeycloakAuditClassifier {

	/** realm-management 클라이언트 역할은 이 접두사를 붙여 role 열에 적는다. */
	public static final String REALM_MANAGEMENT = "realm-management";

	private final ObjectMapper objectMapper;

	public KeycloakAuditClassifier(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * @param realmManagementClientUuid realm-management 클라이언트의 내부 id. 클라이언트 역할 매핑이 관리 권한인지 가른다
	 * @param lastKnownEnabled 사용자의 마지막으로 기록된 활성 상태. 모르면 빈 Optional(새 계정은 활성으로 본다)
	 */
	public List<KeycloakAuditRow> classify(KeycloakAdminEvent event, String realmManagementClientUuid,
			Function<String, Optional<Boolean>> lastKnownEnabled) {
		String path = event.resourcePath() == null ? "" : event.resourcePath();
		String[] parts = path.split("/");
		String operation = event.operationType() == null ? "" : event.operationType();
		boolean created = "CREATE".equals(operation);
		boolean deleted = "DELETE".equals(operation);
		Rows rows = new Rows(event, path);

		// users/{u}/role-mappings/realm, groups/{g}/role-mappings/realm
		if (parts.length == 4 && "role-mappings".equals(parts[2]) && "realm".equals(parts[3]) && (created || deleted)) {
			boolean user = "users".equals(parts[0]);
			KeycloakAuditEventKind kind = user
					? (created ? KeycloakAuditEventKind.ROLE_GRANTED : KeycloakAuditEventKind.ROLE_REVOKED)
					: (created ? KeycloakAuditEventKind.GROUP_ROLE_GRANTED : KeycloakAuditEventKind.GROUP_ROLE_REVOKED);
			rows.perRole(kind, user ? parts[1] : null, groupReference(user ? null : parts[1]), roleNames(event, null));
			return rows.list;
		}
		// users/{u}/role-mappings/clients/{c}, groups/{g}/role-mappings/clients/{c} — realm-management 것만
		if (parts.length == 5 && "role-mappings".equals(parts[2]) && "clients".equals(parts[3]) && (created || deleted)) {
			if (realmManagementClientUuid == null || !realmManagementClientUuid.equals(parts[4])) return List.of();
			boolean user = "users".equals(parts[0]);
			KeycloakAuditEventKind kind = created
					? KeycloakAuditEventKind.MANAGEMENT_ROLE_GRANTED : KeycloakAuditEventKind.MANAGEMENT_ROLE_REVOKED;
			rows.perRole(kind, user ? parts[1] : null, groupReference(user ? null : parts[1]),
					roleNames(event, REALM_MANAGEMENT));
			return rows.list;
		}
		// users/{u}/groups/{g}
		if (parts.length == 4 && "users".equals(parts[0]) && "groups".equals(parts[2]) && (created || deleted)) {
			Map<String, Object> reference = groupReference(parts[3]);
			Map<String, Object> group = object(event);
			if (group.get("path") != null) reference.put("groupPath", group.get("path"));
			rows.add(created ? KeycloakAuditEventKind.GROUP_JOINED : KeycloakAuditEventKind.GROUP_LEFT, parts[1], null,
					path, reference);
			return rows.list;
		}
		// roles/{name}/composites, roles-by-id/{id}/composites — 복합 역할의 구성 변경
		if (parts.length == 3 && isRolePath(parts[0]) && "composites".equals(parts[2]) && (created || deleted)) {
			Map<String, Object> reference = roleReference(parts);
			reference.put("operation", created ? "ADD" : "REMOVE");
			reference.put("composites", roleNames(event, null).orElse(List.of()));
			rows.add(KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED, null, roleName(parts), path, reference);
			return rows.list;
		}
		// roles/{name}, roles-by-id/{id} 삭제
		if (parts.length == 2 && isRolePath(parts[0]) && deleted) {
			Map<String, Object> reference = roleReference(parts);
			reference.put("operation", "DELETE");
			rows.add(KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED, null, roleName(parts), path, reference);
			return rows.list;
		}
		// groups/{g} 삭제
		if (parts.length == 2 && "groups".equals(parts[0]) && deleted) {
			rows.add(KeycloakAuditEventKind.GROUP_DELETED, null, null, path, groupReference(parts[1]));
			return rows.list;
		}
		// users/{u}
		if (parts.length == 2 && "users".equals(parts[0])) {
			String subject = parts[1];
			if (deleted) {
				rows.add(KeycloakAuditEventKind.USER_DELETED, subject, null, path, new LinkedHashMap<>());
			} else if (created) {
				userCreatedWithAccess(event, subject, rows);
			} else if ("UPDATE".equals(operation)) {
				Object enabled = object(event).get("enabled");
				if (enabled instanceof Boolean now) {
					// 이전 값은 Keycloak이 주지 않는다. 마지막으로 기록한 상태와 달라졌을 때만 남긴다(새 계정은 활성으로 본다).
					boolean before = lastKnownEnabled.apply(subject).orElse(true);
					if (before != now) {
						rows.add(now ? KeycloakAuditEventKind.USER_ENABLED : KeycloakAuditEventKind.USER_DISABLED, subject,
								null, path, new LinkedHashMap<>());
					}
				}
			}
			return rows.list;
		}
		// events/config 변경, events·admin-events 삭제 — 감사를 끄거나 지우는 행위
		if ("events/config".equals(path) || (deleted && ("events".equals(path) || "admin-events".equals(path)))) {
			Map<String, Object> reference = new LinkedHashMap<>();
			reference.put("operation", operation);
			Map<String, Object> config = object(event);
			for (String key : List.of("adminEventsEnabled", "adminEventsDetailsEnabled", "eventsEnabled")) {
				if (config.containsKey(key)) reference.put(key, config.get(key));
			}
			rows.add(KeycloakAuditEventKind.EVENT_CONFIG_CHANGED, null, null, path, reference);
			return rows.list;
		}
		return List.of();
	}

	/** 계정을 만들며 넣은 그룹·realm 역할·realm-management 역할. 없으면 저장하지 않는다(이후 가입·부여가 따로 기록된다). */
	@SuppressWarnings("unchecked")
	private void userCreatedWithAccess(KeycloakAdminEvent event, String subject, Rows rows) {
		Map<String, Object> user = object(event);
		for (Object group : list(user.get("groups"))) {
			Map<String, Object> reference = new LinkedHashMap<>();
			reference.put("groupPath", group);
			// 한 이벤트의 여러 그룹 행을 멱등 키로 가르려고 경로 자리에 그룹 경로를 쓴다.
			rows.add(KeycloakAuditEventKind.USER_CREATED_WITH_ACCESS, subject, null, "groups" + group, reference);
		}
		for (Object role : list(user.get("realmRoles"))) {
			rows.add(KeycloakAuditEventKind.USER_CREATED_WITH_ACCESS, subject, String.valueOf(role), rows.path,
					new LinkedHashMap<>());
		}
		if (user.get("clientRoles") instanceof Map<?, ?> clientRoles) {
			for (Object role : list(((Map<String, Object>) clientRoles).get(REALM_MANAGEMENT))) {
				rows.add(KeycloakAuditEventKind.USER_CREATED_WITH_ACCESS, subject, REALM_MANAGEMENT + "/" + role, rows.path,
						new LinkedHashMap<>());
			}
		}
	}

	private static boolean isRolePath(String first) {
		return "roles".equals(first) || "roles-by-id".equals(first);
	}

	/** roles/{name}이면 이름, roles-by-id/{id}면 이름을 모르므로 null(reference에 id를 둔다). */
	private static String roleName(String[] parts) {
		return "roles".equals(parts[0]) ? parts[1] : null;
	}

	private static Map<String, Object> roleReference(String[] parts) {
		Map<String, Object> reference = new LinkedHashMap<>();
		if ("roles-by-id".equals(parts[0])) reference.put("roleId", parts[1]);
		return reference;
	}

	private static Map<String, Object> groupReference(String groupId) {
		Map<String, Object> reference = new LinkedHashMap<>();
		if (groupId != null) reference.put("groupId", groupId);
		return reference;
	}

	/** 상세에 담긴 역할 이름들. 상세가 없으면 빈 Optional(역할을 모르는 것과 역할이 0개인 것을 가른다). */
	private Optional<List<String>> roleNames(KeycloakAdminEvent event, String prefix) {
		if (event.representation() == null) return Optional.empty();
		List<String> names = new ArrayList<>();
		for (Object role : list(parse(event.representation()))) {
			if (role instanceof Map<?, ?> map && map.get("name") != null) {
				names.add(prefix == null ? String.valueOf(map.get("name")) : prefix + "/" + map.get("name"));
			}
		}
		return Optional.of(names);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> object(KeycloakAdminEvent event) {
		Object parsed = event.representation() == null ? null : parse(event.representation());
		return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private Object parse(String json) {
		try {
			return objectMapper.readValue(json, Object.class);
		} catch (JacksonException invalid) {
			return null;
		}
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	/** 한 이벤트에서 나오는 행들. 행위자와 시각·이벤트 id는 모두 같다. */
	private static final class Rows {
		private final KeycloakAdminEvent event;
		private final String path;
		private final List<KeycloakAuditRow> list = new ArrayList<>();

		Rows(KeycloakAdminEvent event, String path) {
			this.event = event;
			this.path = path;
		}

		void add(KeycloakAuditEventKind kind, String targetSubject, String role, String targetPath,
				Map<String, Object> reference) {
			list.add(new KeycloakAuditRow(eventId(event), kind, Instant.ofEpochMilli(event.time()), actorSubject(event),
					actor(event), targetSubject, role, targetPath, reference));
		}

		/** 역할마다 한 행. 상세가 없어 역할을 모르면 역할 없이 한 행을 남긴다. */
		void perRole(KeycloakAuditEventKind kind, String targetSubject, Map<String, Object> reference,
				Optional<List<String>> roles) {
			if (roles.isEmpty()) {
				Map<String, Object> missing = new LinkedHashMap<>(reference);
				missing.put("detailsMissing", true);
				add(kind, targetSubject, null, path, missing);
				return;
			}
			for (String role : roles.get()) add(kind, targetSubject, role, path, new LinkedHashMap<>(reference));
		}
	}

	/**
	 * Keycloak 26.0은 admin event에 id를 주지 않는다(실제 Keycloak 통합 테스트로 확인). 그때는 (시각, 작업, 경로, 상세의 해시)로
	 * 대신한다 — 같은 밀리초에 같은 경로의 같은 작업이 겹쳐도 상세가 다르면 다른 이벤트로 본다. 해시는 상세 원문을 남기지 않는다.
	 */
	static String eventId(KeycloakAdminEvent event) {
		if (event.id() != null) return event.id();
		String base = "t:" + event.time() + ":" + event.operationType() + ":" + event.resourcePath();
		return event.representation() == null ? base : base + ":" + digest(event.representation());
	}

	private static String digest(String value) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hash, 0, 8);
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String actorSubject(KeycloakAdminEvent event) {
		return event.authDetails() == null ? null : event.authDetails().userId();
	}

	/** 행위자 realm·client만 남긴다. IP 주소는 저장하지 않는다. */
	private static Map<String, Object> actor(KeycloakAdminEvent event) {
		Map<String, Object> actor = new LinkedHashMap<>();
		if (event.authDetails() != null) {
			if (event.authDetails().realmId() != null) actor.put("realmId", event.authDetails().realmId());
			if (event.authDetails().clientId() != null) actor.put("clientId", event.authDetails().clientId());
		}
		return actor;
	}
}
