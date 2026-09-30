package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakAdminEvent;
import java.util.List;
import java.util.Map;
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
	private static final Map<String, String> ROLES_BY_ID = Map.of("r-1", "PLATFORM_ADMIN");
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
		assertThat(classifier.classify(disabledUpdate, context(subject -> Optional.of(false)))).isEmpty();
		assertThat(classifier.classify(enabledUpdate, context(subject -> Optional.of(false))))
				.extracting(KeycloakAuditRow::kind).containsExactly(KeycloakAuditEventKind.USER_ENABLED);
	}

	@Test
	void movingAGroupUnderAnotherOrToTheTopIsRecorded() {
		assertThat(classify(event("UPDATE", "groups/parent/children", "{\"id\":\"moved\",\"name\":\"x\"}")))
				.singleElement().satisfies(row -> {
					assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.GROUP_MOVED);
					assertThat(row.reference()).containsEntry("groupId", "moved").containsEntry("newParentId", "parent");
				});
		assertThat(classify(event("UPDATE", "groups", "{\"id\":\"moved\"}"))).singleElement()
				.satisfies(row -> assertThat(row.reference()).containsEntry("newParentId", null));
		// 빈 하위 그룹을 새로 만드는 것은 권한을 바꾸지 않는다.
		assertThat(classify(event("CREATE", "groups/parent/children", "{\"id\":\"new\"}"))).isEmpty();
	}

	@Test
	void creatingRenamingAndDeletingRealmRolesIsRecordedWithNamesEvenByIdOnly() {
		assertThat(classify(event("CREATE", "roles/PLATFORM_ADMIN", "{\"name\":\"PLATFORM_ADMIN\"}")))
				.extracting(KeycloakAuditRow::role).containsExactly("PLATFORM_ADMIN");
		assertThat(classify(event("UPDATE", "roles/viewer", "{\"name\":\"PLATFORM_ADMIN\"}"))).singleElement()
				.satisfies(row -> {
					assertThat(row.role()).isEqualTo("PLATFORM_ADMIN");
					assertThat(row.reference()).containsEntry("before", "viewer").containsEntry("after", "PLATFORM_ADMIN");
				});
		// 이름이 그대로인 수정(설명 등)은 남기지 않는다.
		assertThat(classify(event("UPDATE", "roles/viewer", "{\"name\":\"viewer\",\"description\":\"d\"}"))).isEmpty();
		// roles-by-id 삭제에는 상세가 없다 — 저장해 둔 이름으로 되찾는다.
		assertThat(classify(event("DELETE", "roles-by-id/r-1", null))).singleElement().satisfies(row -> {
			assertThat(row.role()).isEqualTo("PLATFORM_ADMIN");
			assertThat(row.reference()).containsEntry("roleId", "r-1").containsEntry("operation", "DELETE");
		});
	}

	@Test
	void clientRoleCompositesAreRecordedToo() {
		String child = "[{\"name\":\"PLATFORM_ADMIN\"}]";

		assertThat(classify(event("CREATE", "clients/" + REALM_MANAGEMENT + "/roles/view-users/composites", child)))
				.singleElement().satisfies(row -> {
					assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.ROLE_DEFINITION_CHANGED);
					assertThat(row.role()).isEqualTo("realm-management/view-users");
					assertThat(row.reference()).containsEntry("operation", "ADD");
				});
		assertThat(classify(event("DELETE", "roles-by-id/r-1/composites", child))).extracting(KeycloakAuditRow::role)
				.containsExactly("PLATFORM_ADMIN");
	}

	@Test
	void tokenMappersAreRecordedWithTypeAndRoleButNotTheirConfig() {
		String mapper = "{\"name\":\"hr\",\"protocolMapper\":\"oidc-hardcoded-role-mapper\",\"config\":{\"role\":\"PLATFORM_ADMIN\",\"secret\":\"s\"}}";

		assertThat(classify(event("CREATE", "clients/c1/protocol-mappers/models/m1", mapper))).singleElement()
				.satisfies(row -> {
					assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.TOKEN_MAPPER_CHANGED);
					assertThat(row.role()).isEqualTo("PLATFORM_ADMIN");
					assertThat(row.reference()).containsEntry("mapperType", "oidc-hardcoded-role-mapper")
							.doesNotContainKey("config");
				});
		assertThat(classify(event("CREATE", "identity-provider/instances/ext/mappers/m2",
				"{\"identityProviderMapper\":\"oidc-hardcoded-role-idp-mapper\",\"config\":{\"role\":\"PLATFORM_ADMIN\"}}")))
				.extracting(KeycloakAuditRow::kind).containsExactly(KeycloakAuditEventKind.TOKEN_MAPPER_CHANGED);
		assertThat(classify(event("DELETE", "client-scopes/s1/protocol-mappers/models/m3", null)))
				.extracting(KeycloakAuditRow::kind).containsExactly(KeycloakAuditEventKind.TOKEN_MAPPER_CHANGED);
	}

	@Test
	void turningAdminEventsOffThroughTheRealmIsRecordedButOtherRealmChangesAreNot() {
		assertThat(classify(event("UPDATE", null, "{\"adminEventsEnabled\":false}"))).singleElement().satisfies(row -> {
			assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.EVENT_CONFIG_CHANGED);
			assertThat(row.targetPath()).isEqualTo("realm");
			assertThat(row.reference()).containsEntry("adminEventsEnabled", false);
		});
		assertThat(classify(event("UPDATE", null, "{\"displayName\":\"x\"}"))).isEmpty();
	}

	@Test
	void anAccountCreatedDisabledIsRecordedSoTurningItOnLaterShows() {
		assertThat(classify(event("CREATE", "users/u1", "{\"username\":\"off\",\"enabled\":false}")))
				.extracting(KeycloakAuditRow::kind).containsExactly(KeycloakAuditEventKind.USER_DISABLED);
	}

	@Test
	void theEnabledStateIsLookedUpAsOfTheEventTime() {
		java.util.List<java.time.Instant> asked = new java.util.ArrayList<>();
		KeycloakAuditClassifier.Context context = new KeycloakAuditClassifier.Context(REALM_MANAGEMENT, (subject, at) -> {
			asked.add(at);
			return Optional.empty();
		}, id -> Optional.empty());

		classifier.classify(event("UPDATE", "users/u1", "{\"enabled\":false}"), context);

		// 지금 상태가 아니라 그 이벤트 시각 이전 상태를 묻는다 — 다시 읽어도 결과가 같다.
		assertThat(asked).containsExactly(java.time.Instant.ofEpochMilli(1000L));
	}

	@Test
	void aTooLongValueIsCutToTheColumnAndLeavesADigest() {
		KeycloakAuditRow row = new KeycloakAuditRow("e", KeycloakAuditEventKind.TOKEN_MAPPER_CHANGED, java.time.Instant.EPOCH,
				null, Map.of(), null, "r".repeat(300), "p".repeat(2000), Map.of("operation", "CREATE"));

		KeycloakAuditRow fitted = KeycloakAuditStore.fitted(row);

		assertThat(fitted.role()).hasSize(255);
		assertThat(fitted.targetPath()).hasSize(1024);
		assertThat(fitted.reference()).containsKeys("roleTruncated", "targetPathTruncated").containsEntry("operation", "CREATE");
	}

	@Test
	void theFailureSummaryNeverCarriesRowValuesOrHosts() {
		assertThat(KeycloakAuditCollector.summary(new org.springframework.dao.DataIntegrityViolationException(
				"Failing row contains (secret-subject, PLATFORM_ADMIN)"))).isEqualTo("DataIntegrityViolationException");
		assertThat(KeycloakAuditCollector.summary(new IllegalStateException("connect to 10.0.0.5 failed")))
				.isEqualTo("IllegalStateException");
		assertThat(KeycloakAuditCollector.summary(new KeycloakAuditCollector.CollectorProblem("view-clients 권한 확인")))
				.isEqualTo("view-clients 권한 확인");
	}

	@Test
	void anEventThatCannotBeClassifiedBecomesAnUnreadableRowWithoutItsDetails() {
		KeycloakAuditRow row = KeycloakAuditClassifier.unreadable(event("UPDATE", "users/u1", "{\"secret\":\"x\"}"),
				new ClassCastException("boom"));

		assertThat(row.kind()).isEqualTo(KeycloakAuditEventKind.EVENT_UNREADABLE);
		assertThat(row.reference()).containsEntry("error", "ClassCastException").doesNotContainValue("boom");
		assertThat(row.reference().toString()).doesNotContain("secret");
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
	void anEventWithoutAnIdFallsBackToTimeOperationAndAShortDigest() {
		KeycloakAdminEvent first = new KeycloakAdminEvent(null, 1000L, null, "CREATE", null, "users/u1/role-mappings/realm",
				"[{\"name\":\"A\"}]");
		KeycloakAdminEvent second = new KeycloakAdminEvent(null, 1000L, null, "CREATE", null, "users/u1/role-mappings/realm",
				"[{\"name\":\"B\"}]");
		KeycloakAdminEvent longPath = new KeycloakAdminEvent(null, 1000L, null, "DELETE", null, "roles/" + "x".repeat(400), null);

		assertThat(KeycloakAuditClassifier.eventId(first)).startsWith("t:1000:CREATE:")
				.isNotEqualTo(KeycloakAuditClassifier.eventId(second)).doesNotContain("name").doesNotContain("users");
		// 경로를 통째로 넣지 않아 열 길이(255)를 넘지 않는다.
		assertThat(KeycloakAuditClassifier.eventId(longPath)).hasSizeLessThan(64);
	}

	private List<KeycloakAuditRow> classify(KeycloakAdminEvent event) {
		return classifier.classify(event, context(UNKNOWN));
	}

	private static KeycloakAuditClassifier.Context context(Function<String, Optional<Boolean>> lastKnownEnabled) {
		return new KeycloakAuditClassifier.Context(REALM_MANAGEMENT, (subject, at) -> lastKnownEnabled.apply(subject),
				id -> Optional.ofNullable(ROLES_BY_ID.get(id)));
	}

	private static KeycloakAdminEvent event(String operation, String path, String representation) {
		return new KeycloakAdminEvent("event-1", 1000L,
				new KeycloakAdminEvent.AuthDetails("master", "admin-cli", "admin-user", "10.0.0.1"), operation, null, path,
				representation);
	}
}
