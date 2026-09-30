package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : KeycloakAuditClassifierTest.java
 * Description : Keycloak admin event 분류(#304) 중 실제 Keycloak 없이 빠르게 볼 규칙 — 상세가 꺼진 경우, realm-management가 아닌
 *               클라이언트 역할, 활성 상태가 그대로인 사용자 수정, 행위자 IP 비저장, 이벤트 id가 없을 때의 대체 키.
 *               실제 이벤트 모양은 KeycloakAuditKeycloakTest가 본다.
 */
class KeycloakAuditClassifierTest {

	private static final String REALM_MANAGEMENT = "rm-uuid";
	private static final Function<String, Optional<Boolean>> UNKNOWN = subject -> Optional.empty();
	private final KeycloakAuditClassifier classifier = new KeycloakAuditClassifier(new JsonMapper());

	@Test
	void aRoleMappingWithoutDetailsStillLeavesOneRowMarkedAsMissingDetails() {
		List<KeycloakAuditRow> rows = classify(event("CREATE", "users/u1/role-mappings/realm", null));

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.ROLE_GRANTED);
			assertThat(row.role()).isNull();
			assertThat(row.reference()).containsEntry("detailsMissing", true);
		});
	}

	@Test
	void oneRowPerRoleAndGroupRoleMappingsAreTheirOwnKind() {
		String roles = "[{\"name\":\"PLATFORM_ADMIN\"},{\"name\":\"USER\"}]";

		assertThat(classify(event("CREATE", "users/u1/role-mappings/realm", roles)))
				.extracting(KeycloakAuditRow::role).containsExactly("PLATFORM_ADMIN", "USER");
		assertThat(classify(event("DELETE", "groups/g1/role-mappings/realm", roles)))
				.allSatisfy(row -> {
					assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.GROUP_ROLE_REVOKED);
					assertThat(row.targetSubject()).isNull();
					assertThat(row.reference()).containsEntry("groupId", "g1");
				});
	}

	@Test
	void onlyRealmManagementClientRolesAreCollected() {
		String roles = "[{\"name\":\"manage-users\"}]";

		assertThat(classify(event("CREATE", "users/u1/role-mappings/clients/other-client", roles))).isEmpty();
		assertThat(classify(event("CREATE", "users/u1/role-mappings/clients/" + REALM_MANAGEMENT, roles)))
				.singleElement().satisfies(row -> {
					assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.MANAGEMENT_ROLE_GRANTED);
					assertThat(row.role()).isEqualTo("realm-management/manage-users");
				});
	}

	@Test
	void anUpdateIsRecordedOnlyWhenTheEnabledStateActuallyChanges() {
		KeycloakAdminEvent enabledUpdate = event("UPDATE", "users/u1", "{\"enabled\":true,\"firstName\":\"앨리스\"}");
		KeycloakAdminEvent disabledUpdate = event("UPDATE", "users/u1", "{\"enabled\":false}");

		// 새 계정은 활성으로 본다 — 활성인 채 이름만 바꾼 수정은 남지 않는다.
		assertThat(classify(enabledUpdate)).isEmpty();
		assertThat(classify(disabledUpdate)).extracting(KeycloakAuditRow::kind)
				.containsExactly(KeycloakAuditEventKind.USER_DISABLED);
		assertThat(classifier.classify(disabledUpdate, REALM_MANAGEMENT, subject -> Optional.of(false))).isEmpty();
		assertThat(classifier.classify(enabledUpdate, REALM_MANAGEMENT, subject -> Optional.of(false)))
				.extracting(KeycloakAuditRow::kind).containsExactly(KeycloakAuditEventKind.USER_ENABLED);
	}

	@Test
	void theActorKeepsRealmAndClientButNeverTheIpAddress() {
		KeycloakAuditRow row = classify(event("DELETE", "users/u1", null)).get(0);

		assertThat(row.actorSubject()).isEqualTo("admin-user");
		assertThat(row.actor()).containsOnlyKeys("realmId", "clientId");
	}

	@Test
	void unrelatedChangesAreSkipped() {
		assertThat(classify(event("UPDATE", "clients/c1", "{}"))).isEmpty();
		assertThat(classify(event("CREATE", "users", "{}"))).isEmpty();
		assertThat(classify(event("CREATE", "users/u1", "{\"username\":\"plain\"}"))).isEmpty();
	}

	@Test
	void anEventWithoutAnIdFallsBackToTimeOperationPathAndADigestOfTheDetails() {
		KeycloakAdminEvent withoutDetails = new KeycloakAdminEvent(null, 1000L, null, "DELETE", "USER", "users/u1", null);
		KeycloakAdminEvent first = new KeycloakAdminEvent(null, 1000L, null, "CREATE", null, "users/u1/role-mappings/realm",
				"[{\"name\":\"A\"}]");
		KeycloakAdminEvent second = new KeycloakAdminEvent(null, 1000L, null, "CREATE", null, "users/u1/role-mappings/realm",
				"[{\"name\":\"B\"}]");

		assertThat(KeycloakAuditClassifier.eventId(withoutDetails)).isEqualTo("t:1000:DELETE:users/u1");
		// 같은 밀리초·같은 경로·같은 작업이라도 상세가 다르면 다른 이벤트다. 해시에 상세 원문은 남지 않는다.
		assertThat(KeycloakAuditClassifier.eventId(first)).isNotEqualTo(KeycloakAuditClassifier.eventId(second))
				.doesNotContain("name");
	}

	private List<KeycloakAuditRow> classify(KeycloakAdminEvent event) {
		return classifier.classify(event, REALM_MANAGEMENT, UNKNOWN);
	}

	private static KeycloakAdminEvent event(String operation, String path, String representation) {
		return new KeycloakAdminEvent("event-1", 1000L,
				new KeycloakAdminEvent.AuthDetails("master", "admin-cli", "admin-user", "10.0.0.1"), operation, null, path,
				representation);
	}
}
