package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : KeycloakAuditClassifier.java
 * Description : Keycloak admin event 한 건을 keycloak_adt 행으로 바꾼다(#304). 권한과 관련된 것만 남기고 나머지는 빈 목록이다.
 *               종류는 resourceType이 아니라 리소스 경로 모양과 작업 종류로 가른다 — 경로가 Admin API 주소 그 자체라 더 안정적이다
 *               (partial import도 사용자마다 `users/{id}` CREATE로 남아 같은 규칙에 걸린다).
 *               상세(representation) 원문은 저장하지 않고 역할 이름·그룹·enabled·매퍼 종류 같은 필요한 값만 뽑는다.
 *               상세가 꺼져 있어 역할 이름을 모르면 역할 없이 한 행을 남기고 reference에 detailsMissing을 적는다.
 *               경로와 상세 모양은 실제 Keycloak 26으로 확인했다(KeycloakAuditKeycloakTest).
 */
@Component
public class KeycloakAuditClassifier {

	/** realm-management 클라이언트 역할은 이 접두사를 붙여 role 열에 적는다. */
	public static final String REALM_MANAGEMENT = "realm-management";

	private final ObjectMapper objectMapper;

	public KeycloakAuditClassifier(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 분류에 필요한 현재 상태. */
	/**
	 * @param enabledBefore 계정의, 주어진 시각 <b>이전</b> 마지막으로 기록된 활성 상태. 시각을 기준으로 해야 이미 처리한 이벤트를
	 *                      다시 읽을 때 결과가 같다(지금 상태로 비교하면 나중 변경 때문에 가짜 행이 생긴다).
	 */
	public record Context(String realmManagementClientUuid, BiFunction<String, Instant, Optional<Boolean>> enabledBefore,
			Function<String, Optional<String>> realmRoleNameById) {
	}

	public List<KeycloakAuditRow> classify(KeycloakAdminEvent event, Context context) {
		String path = event.resourcePath() == null ? "" : event.resourcePath();
		String[] parts = path.isEmpty() ? new String[0] : path.split("/");
		String operation = event.operationType() == null ? "" : event.operationType();
		boolean created = "CREATE".equals(operation);
		boolean updated = "UPDATE".equals(operation);
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
			if (!parts[4].equals(context.realmManagementClientUuid())) return List.of();
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
			Object groupPath = object(event).get("path");
			if (groupPath != null) reference.put("groupPath", groupPath);
			rows.add(created ? KeycloakAuditEventKind.GROUP_JOINED : KeycloakAuditEventKind.GROUP_LEFT, parts[1], null,
					path, reference);
			return rows.list;
		}
		// 그룹 이동: groups/{부모}/children UPDATE(부모 아래로), groups UPDATE(최상위로). 하위 그룹은 부모의 역할을 물려받으므로
		// 옮기는 것만으로 그 그룹 구성원의 권한이 바뀐다. 하위 그룹 새로 만들기(CREATE)는 빈 그룹이라 권한이 바뀌지 않는다.
		if (updated && ((parts.length == 3 && "groups".equals(parts[0]) && "children".equals(parts[2]))
				|| (parts.length == 1 && "groups".equals(parts[0])))) {
			Object moved = object(event).get("id");
			Map<String, Object> reference = groupReference(moved == null ? null : String.valueOf(moved));
			reference.put("newParentId", parts.length == 3 ? parts[1] : null);
			rows.add(KeycloakAuditEventKind.GROUP_MOVED, null, null, path, reference);
			return rows.list;
		}
		// 복합 역할 구성 변경: roles/{name}/composites, roles-by-id/{id}/composites, clients/{c}/roles/{r}/composites
		if (created || deleted) {
			Optional<String> parent = compositeParent(parts, context);
			if (parent != null) {
				Map<String, Object> reference = new LinkedHashMap<>(roleReference(parts));
				reference.put("operation", created ? "ADD" : "REMOVE");
				reference.put("composites", roleNames(event, null).orElse(List.of()));
				rows.add(KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED, null, parent.orElse(null), path, reference);
				return rows.list;
			}
		}
		// realm 역할 만들기·이름 바꾸기·삭제: roles/{name}, roles-by-id/{id}. 이름이 바뀌면 그 역할 보유자 전원의 권한 이름이 바뀐다.
		if (parts.length == 2 && ("roles".equals(parts[0]) || "roles-by-id".equals(parts[0])) && (created || updated || deleted)) {
			String before = "roles".equals(parts[0]) ? parts[1] : context.realmRoleNameById().apply(parts[1]).orElse(null);
			Object after = object(event).get("name");
			if (updated && after != null && after.equals(before)) return List.of(); // 설명 등 이름 외 변경
			Map<String, Object> reference = new LinkedHashMap<>(roleReference(parts));
			// 생성 상세에는 역할 id가 있다. 수집기가 이것으로 id→이름 표를 이어서 고친다(뒤이은 roles-by-id 이벤트의 이름).
			if (created && object(event).get("id") != null) reference.put("roleId", String.valueOf(object(event).get("id")));
			reference.put("operation", created ? "CREATE" : updated ? "RENAME" : "DELETE");
			if (updated) {
				reference.put("before", before);
				reference.put("after", after);
			}
			rows.add(KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED, null,
					updated && after != null ? String.valueOf(after) : before, path, reference);
			return rows.list;
		}
		// groups/{g} 삭제
		if (parts.length == 2 && "groups".equals(parts[0]) && deleted) {
			rows.add(KeycloakAuditEventKind.GROUP_DELETED, null, null, path, groupReference(parts[1]));
			return rows.list;
		}
		// users/{u}
		if (parts.length == 2 && "users".equals(parts[0])) {
			user(event, parts[1], operation, context, rows);
			return rows.list;
		}
		// 토큰에 역할을 실을 수 있는 매퍼. BFF는 토큰의 역할을 믿으므로 역할 매핑 없이도 권한이 생긴다.
		if (isMapperPath(parts) && (created || updated || deleted)) {
			mappers(event, parts, operation, rows);
			return rows.list;
		}
		// 감사 설정 변경: events/config, 또는 realm 전체 수정(PUT realm, 경로 없음)에 admin event 설정이 실린 경우.
		// admin event를 끄는 PUT realm은 이벤트로 남는다(끈 뒤의 변경은 남지 않는다) — 1분 안에 껐다 켜도 이 이벤트로 드러난다.
		if (updated && ("events/config".equals(path) || path.isEmpty())) {
			Map<String, Object> config = object(event);
			Map<String, Object> reference = new LinkedHashMap<>();
			for (String key : List.of("adminEventsEnabled", "adminEventsDetailsEnabled", "eventsEnabled")) {
				if (config.containsKey(key)) reference.put(key, config.get(key));
			}
			if (path.isEmpty() && reference.isEmpty()) return List.of(); // 감사와 무관한 realm 설정 변경
			rows.add(KeycloakAuditEventKind.EVENT_CONFIG_CHANGED, null, null, path.isEmpty() ? "realm" : path, reference);
			return rows.list;
		}
		return List.of();
	}

	private void user(KeycloakAdminEvent event, String subject, String operation, Context context, Rows rows) {
		Map<String, Object> user = object(event);
		switch (operation) {
			case "DELETE" -> rows.add(KeycloakAuditEventKind.USER_DELETED, subject, null, rows.path, new LinkedHashMap<>());
			case "CREATE" -> {
				userCreatedWithAccess(user, subject, rows);
				// 비활성으로 만든 계정은 나중에 켜는 순간이 권한 획득이므로, 만들 때의 상태를 남겨 둔다.
				if (Boolean.FALSE.equals(user.get("enabled"))) {
					rows.add(KeycloakAuditEventKind.USER_DISABLED, subject, null, rows.path, new LinkedHashMap<>());
				}
			}
			case "UPDATE" -> {
				if (user.get("enabled") instanceof Boolean now) {
					// 이전 값은 Keycloak이 주지 않는다. 마지막으로 기록한 상태와 달라졌을 때만 남긴다(모르면 활성으로 본다 —
					// 수집 전부터 비활성이던 계정은 기준선이 USER_DISABLED로 남겨 둔다).
					boolean before = context.enabledBefore().apply(subject, Instant.ofEpochMilli(event.time())).orElse(true);
					if (before != now) {
						rows.add(now ? KeycloakAuditEventKind.USER_ENABLED : KeycloakAuditEventKind.USER_DISABLED, subject,
								null, rows.path, new LinkedHashMap<>());
					}
				}
			}
			default -> {
			}
		}
	}

	/** 계정을 만들며 넣은 그룹·realm 역할·realm-management 역할(partial import 포함). 없으면 저장하지 않는다. */
	@SuppressWarnings("unchecked")
	private void userCreatedWithAccess(Map<String, Object> user, String subject, Rows rows) {
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

	/**
	 * 복합 역할 구성 변경 경로면 부모 역할 이름(모르면 빈 Optional), 아니면 null.
	 * realm-management 클라이언트 역할은 접두사를 붙이고, 다른 클라이언트 역할은 이름만 적는다(reference에 클라이언트 id).
	 */
	private static Optional<String> compositeParent(String[] parts, Context context) {
		if (parts.length == 3 && "composites".equals(parts[2])) {
			if ("roles".equals(parts[0])) return Optional.of(parts[1]);
			if ("roles-by-id".equals(parts[0])) return context.realmRoleNameById().apply(parts[1]);
		}
		if (parts.length == 5 && "clients".equals(parts[0]) && "roles".equals(parts[2]) && "composites".equals(parts[4])) {
			return Optional.of(parts[1].equals(context.realmManagementClientUuid()) ? REALM_MANAGEMENT + "/" + parts[3] : parts[3]);
		}
		return null;
	}

	private static Map<String, Object> roleReference(String[] parts) {
		Map<String, Object> reference = new LinkedHashMap<>();
		if ("roles-by-id".equals(parts[0])) reference.put("roleId", parts[1]);
		if ("clients".equals(parts[0])) reference.put("clientId", parts[1]);
		return reference;
	}

	/** clients/{c}/protocol-mappers/..., client-scopes/{s}/protocol-mappers/..., identity-provider/instances/{a}/mappers... */
	private static boolean isMapperPath(String[] parts) {
		if (parts.length >= 3 && ("clients".equals(parts[0]) || "client-scopes".equals(parts[0]))
				&& "protocol-mappers".equals(parts[2])) return true;
		return parts.length >= 4 && "identity-provider".equals(parts[0]) && "instances".equals(parts[1])
				&& "mappers".equals(parts[3]);
	}

	/** 매퍼 종류와 연결된 역할만 남긴다. 설정 원문은 저장하지 않는다. add-models처럼 여러 개면 매퍼마다 한 행. */
	@SuppressWarnings("unchecked")
	private void mappers(KeycloakAdminEvent event, String[] parts, String operation, Rows rows) {
		Object parsed = event.representation() == null ? null : parse(event.representation());
		List<?> items = parsed instanceof List<?> list ? list : parsed == null ? java.util.Collections.singletonList(null) : List.of(parsed);
		for (Object item : items) {
			Map<String, Object> mapper = item instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
			Map<String, Object> reference = new LinkedHashMap<>();
			reference.put("operation", operation);
			reference.put("container", parts[0] + "/" + parts[1] + ("identity-provider".equals(parts[0]) ? "/" + parts[2] : ""));
			Object type = mapper.containsKey("protocolMapper") ? mapper.get("protocolMapper") : mapper.get("identityProviderMapper");
			if (type != null) reference.put("mapperType", type);
			if (mapper.get("name") != null) reference.put("mapperName", mapper.get("name"));
			Object role = mapper.get("config") instanceof Map<?, ?> config ? config.get("role") : null;
			// 한 이벤트의 여러 매퍼를 가르려고 이름을 경로 뒤에 붙인다.
			String targetPath = items.size() > 1 && mapper.get("name") != null ? rows.path + "#" + mapper.get("name") : rows.path;
			rows.add(KeycloakAuditEventKind.TOKEN_MAPPER_CHANGED, null, role == null ? null : String.valueOf(role), targetPath,
					reference);
		}
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

	/**
	 * 상세를 읽지 못하면 예외를 던진다 — 조용히 빈 값으로 두면 역할·그룹이 없는 것으로 보여 아무 행도 남지 않는다.
	 * 수집기가 이 예외를 받아 그 이벤트를 EVENT_UNREADABLE로 남긴다.
	 */
	private Object parse(String json) {
		try {
			return objectMapper.readValue(json, Object.class);
		} catch (JacksonException invalid) {
			throw new IllegalArgumentException("admin event 상세를 읽을 수 없다");
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
	 * Keycloak 26.0은 admin event에 id를 주지 않는다(실제 Keycloak 통합 테스트로 확인). 그때는 (시각, 작업, 경로와 상세의 해시)로
	 * 대신한다. 같은 밀리초에 같은 경로의 같은 작업이 겹쳐도 상세가 다르면 다른 이벤트다. 경로를 통째로 넣지 않고 해시해
	 * 열 길이(255)를 넘지 않게 하고, 상세 원문도 남기지 않는다.
	 */
	static String eventId(KeycloakAdminEvent event) {
		if (event.id() != null) return event.id();
		String content = event.resourcePath() + "\n" + (event.representation() == null ? "" : event.representation());
		return "t:" + event.time() + ":" + event.operationType() + ":" + digest(content);
	}

	private static String digest(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** 분류하다 실패한 이벤트의 행. 상세 원문과 예외 메시지는 넣지 않고 경로·작업·예외 종류만 남긴다. */
	static KeycloakAuditRow unreadable(KeycloakAdminEvent event, RuntimeException failure) {
		Map<String, Object> reference = new LinkedHashMap<>();
		reference.put("operation", event.operationType());
		reference.put("resourceType", event.resourceType());
		reference.put("error", failure.getClass().getSimpleName());
		String path = event.resourcePath() == null || event.resourcePath().isEmpty() ? "realm" : event.resourcePath();
		return new KeycloakAuditRow(eventId(event), KeycloakAuditEventKind.EVENT_UNREADABLE, Instant.ofEpochMilli(event.time()),
				actorSubject(event), actor(event), null, null, path, reference);
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
