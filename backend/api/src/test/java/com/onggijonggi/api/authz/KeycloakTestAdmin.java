package com.onggijonggi.api.authz;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : KeycloakTestAdmin.java
 * Description : 테스트용 Keycloak 조작 도우미(#304). master realm 관리자 토큰으로 Admin REST를 직접 불러 realm·사용자·그룹·역할을
 *               만들고 바꾼다. 이 조작들이 실제 admin event를 남기고, 수집기가 그것을 읽는다.
 */
final class KeycloakTestAdmin {

	private final String baseUrl;
	private final String realm;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new JsonMapper();

	KeycloakTestAdmin(String baseUrl, String realm) {
		this.baseUrl = baseUrl;
		this.realm = realm;
	}

	/** admin event·상세를 켠 realm과 PLATFORM_ADMIN·USER 역할, 서비스 계정 클라이언트를 만든다. */
	void createRealm(String clientId, String secret) {
		send("POST", "/admin/realms", Map.of("realm", realm, "enabled", true, "adminEventsEnabled", true,
				"adminEventsDetailsEnabled", true,
				"roles", Map.of("realm", List.of(Map.of("name", "PLATFORM_ADMIN"), Map.of("name", "USER"))),
				"clients", List.of(Map.of("clientId", clientId, "secret", secret, "publicClient", false,
						"serviceAccountsEnabled", true, "standardFlowEnabled", false, "directAccessGrantsEnabled", false))));
	}

	/** 서비스 계정에 realm-management 역할들을 준다. */
	void grantServiceAccount(String clientId, List<String> managementRoles) {
		String client = clientUuid(clientId);
		String serviceUser = (String) object(send("GET", realmPath("/clients/" + client + "/service-account-user"), null))
				.get("id");
		grantManagementRoles(serviceUser, managementRoles);
	}

	void grantManagementRoles(String userId, List<String> roles) {
		String realmManagement = clientUuid("realm-management");
		send("POST", realmPath("/users/" + userId + "/role-mappings/clients/" + realmManagement),
				roles.stream().map(role -> object(send("GET", realmPath("/clients/" + realmManagement + "/roles/" + role), null)))
						.toList());
	}

	void revokeManagementRoles(String userId, List<String> roles) {
		String realmManagement = clientUuid("realm-management");
		send("DELETE", realmPath("/users/" + userId + "/role-mappings/clients/" + realmManagement),
				roles.stream().map(role -> object(send("GET", realmPath("/clients/" + realmManagement + "/roles/" + role), null)))
						.toList());
	}

	void grantGroupManagementRoles(String groupId, List<String> roles) {
		String realmManagement = clientUuid("realm-management");
		send("POST", realmPath("/groups/" + groupId + "/role-mappings/clients/" + realmManagement),
				roles.stream().map(role -> object(send("GET", realmPath("/clients/" + realmManagement + "/roles/" + role), null)))
						.toList());
	}

	void revokeGroupRealmRole(String groupId, String role) {
		send("DELETE", realmPath("/groups/" + groupId + "/role-mappings/realm"), List.of(role(role)));
	}

	/** 사용자의 실효 realm 역할 이름(Keycloak이 실제로 권한을 줬는지 확인할 때). */
	List<String> effectiveRealmRoles(String userId) {
		return list(send("GET", realmPath("/users/" + userId + "/role-mappings/realm/composite"), null)).stream()
				.map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList();
	}

	List<String> effectiveClientRoles(String userId, String clientUuid) {
		return list(send("GET", realmPath("/users/" + userId + "/role-mappings/clients/" + clientUuid + "/composite"), null))
				.stream().map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList();
	}

	String createSubGroup(String parentId, String name) {
		return createAndReturnId(realmPath("/groups/" + parentId + "/children"), Map.of("name", name));
	}

	String clientUuid(String clientId) {
		return (String) list(send("GET", realmPath("/clients?clientId=" + encode(clientId)), null)).stream()
				.map(item -> (Map<?, ?>) item).filter(item -> clientId.equals(item.get("clientId"))).findFirst()
				.orElseThrow().get("id");
	}

	String createUser(Map<String, Object> representation) {
		return createAndReturnId(realmPath("/users"), representation);
	}

	void updateUser(String userId, Map<String, Object> changes) {
		send("PUT", realmPath("/users/" + userId), changes);
	}

	void deleteUser(String userId) {
		send("DELETE", realmPath("/users/" + userId), null);
	}

	void grantRealmRole(String userId, String role) {
		send("POST", realmPath("/users/" + userId + "/role-mappings/realm"), List.of(role(role)));
	}

	void revokeRealmRole(String userId, String role) {
		send("DELETE", realmPath("/users/" + userId + "/role-mappings/realm"), List.of(role(role)));
	}

	String createGroup(String name) {
		return createAndReturnId(realmPath("/groups"), Map.of("name", name));
	}

	void deleteGroup(String groupId) {
		send("DELETE", realmPath("/groups/" + groupId), null);
	}

	void grantGroupRealmRole(String groupId, String role) {
		send("POST", realmPath("/groups/" + groupId + "/role-mappings/realm"), List.of(role(role)));
	}

	void joinGroup(String userId, String groupId) {
		send("PUT", realmPath("/users/" + userId + "/groups/" + groupId), null);
	}

	void leaveGroup(String userId, String groupId) {
		send("DELETE", realmPath("/users/" + userId + "/groups/" + groupId), null);
	}

	void createRole(String name) {
		send("POST", realmPath("/roles"), Map.of("name", name));
	}

	void createManagementRole(String name) {
		send("POST", realmPath("/clients/" + clientUuid("realm-management") + "/roles"), Map.of("name", name));
	}

	void addComposite(String role, String child) {
		send("POST", realmPath("/roles/" + encode(role) + "/composites"), List.of(role(child)));
	}

	/** 기존 그룹을 다른 그룹 아래로 옮긴다. */
	void moveGroupUnder(String parentId, String groupId, String name) {
		send("POST", realmPath("/groups/" + parentId + "/children"), Map.of("id", groupId, "name", name));
	}

	/** 기존 그룹을 최상위로 뺀다. */
	void moveGroupToTop(String groupId, String name) {
		send("POST", realmPath("/groups"), Map.of("id", groupId, "name", name));
	}

	void renameRole(String name, String newName) {
		send("PUT", realmPath("/roles/" + encode(name)), Map.of("name", newName));
	}

	String roleId(String name) {
		return (String) role(name).get("id");
	}

	void deleteRoleById(String roleId) {
		send("DELETE", realmPath("/roles-by-id/" + roleId), null);
	}

	void deleteRole(String name) {
		send("DELETE", realmPath("/roles/" + encode(name)), null);
	}

	void addClientRoleComposite(String clientUuid, String clientRole, String realmRole) {
		send("POST", realmPath("/clients/" + clientUuid + "/roles/" + encode(clientRole) + "/composites"),
				List.of(role(realmRole)));
	}

	void addDefaultRole(String realmRole) {
		addComposite("default-roles-" + realm, realmRole);
	}

	void removeDefaultRole(String realmRole) {
		send("DELETE", realmPath("/roles/default-roles-" + realm + "/composites"), List.of(role(realmRole)));
	}

	/** partial import로 역할을 가진 사용자를 만든다. */
	void importUserWithRole(String username, String role) {
		send("POST", realmPath("/partialImport"), Map.of("ifResourceExists", "SKIP",
				"users", List.of(Map.of("username", username, "enabled", true, "realmRoles", List.of(role)))));
	}

	/** 토큰에 역할을 싣는 hardcoded-role 매퍼를 클라이언트에 붙인다. */
	void addHardcodedRoleMapper(String clientId, String role) {
		addHardcodedRoleMapper(clientId, "hardcoded-" + role, role);
	}

	void addHardcodedRoleMapper(String clientId, String name, String role) {
		send("POST", realmPath("/clients/" + clientUuid(clientId) + "/protocol-mappers/models"), Map.of("name", name,
				"protocol", "openid-connect", "protocolMapper", "oidc-hardcoded-role-mapper", "config", Map.of("role", role)));
	}

	/** realm 전체 수정(PUT realm). admin event 설정도 여기로 바꿀 수 있다. */
	void updateRealm(Map<String, Object> changes) {
		send("PUT", "/admin/realms/" + realm, changes);
	}

	String userId(String username) {
		return (String) ((Map<?, ?>) list(send("GET", realmPath("/users?exact=true&username=" + encode(username)), null)).get(0))
				.get("id");
	}

	void setAdminEvents(boolean enabled) {
		Map<?, ?> config = object(send("GET", realmPath("/events/config"), null));
		java.util.Map<String, Object> changed = new java.util.LinkedHashMap<>();
		config.forEach((key, value) -> changed.put(String.valueOf(key), value));
		changed.put("adminEventsEnabled", enabled);
		send("PUT", realmPath("/events/config"), changed);
	}

	private Map<?, ?> role(String name) {
		return object(send("GET", realmPath("/roles/" + encode(name)), null));
	}

	private String createAndReturnId(String path, Object body) {
		HttpResponse<String> response = exchange("POST", path, body);
		String location = response.headers().firstValue("Location").orElseThrow();
		return location.substring(location.lastIndexOf('/') + 1);
	}

	private String realmPath(String path) {
		return "/admin/realms/" + realm + path;
	}

	private String send(String method, String path, Object body) {
		return exchange(method, path, body).body();
	}

	private HttpResponse<String> exchange(String method, String path, Object body) {
		try {
			HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
					.header("Authorization", "Bearer " + masterToken())
					.header("Content-Type", "application/json")
					.method(method, publisher).build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new IllegalStateException(method + " " + path + " → " + response.statusCode() + " " + response.body());
			}
			return response;
		} catch (java.io.IOException | InterruptedException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private String masterToken() throws java.io.IOException, InterruptedException {
		String form = "grant_type=password&client_id=admin-cli&username=admin&password=admin";
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build();
		return (String) object(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).get("access_token");
	}

	private Map<?, ?> object(String body) {
		return json.readValue(body, Map.class);
	}

	private List<?> list(String body) {
		return json.readValue(body, List.class);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
