package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.CutoverValidationResult;
import com.onggijonggi.api.authz.CutoverValidationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Class Name : CutoverValidationPostgresTest.java
 * Description : PostgreSQL의 절체 전 스키마에서 단일 Tenant·최종 Workspace VIEW를 읽기 전용으로 검증한다.
 */
class CutoverValidationPostgresTest extends PostgresSpringTestBase {

	@Autowired private CutoverValidationService validation;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void isolateTenant() {
		jdbc.update("delete from thr");
		jdbc.update("update tnn set status = 'INACTIVE', inactive_at = now() where status = 'ACTIVE'");
	}

	@Test
	void noActiveTenantBlocksOperationalCutover() {
		CutoverValidationResult result = validation.validate(List.of());
		assertThat(result.ready()).isFalse();
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("ACTIVE_TENANT_COUNT");
	}

	@Test
	void directAndCollabUseTheirFinalWorkspacesWithoutStaging() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		grant(fixture.tenant(), fixture.team(), fixture.common());
		grant(fixture.tenant(), fixture.team(), fixture.teamWorkspace());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.ready()).isTrue();
		assertThat(result.tenantId()).isEqualTo(fixture.tenant());
		assertThat(result.failures()).isEmpty();
		assertThat(jdbc.queryForObject("select tnn_id from thr where id = ?", UUID.class, direct)).isNull();
	}

	@Test
	void commonViewDoesNotGrantViewOfCollabWorkspace() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		grant(fixture.tenant(), fixture.team(), fixture.common());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.ready()).isFalse();
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("THREAD_ACTOR_VIEW_MISSING");
	}

	@Test
	void locallyInactiveButKeycloakEnabledOwnerIsStillChecked() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		jdbc.update("update app_user set status = 'INACTIVE', inactive_at = now() where id = ?", owner);
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("THREAD_ACTOR_VIEW_MISSING");
	}

	private Fixture fixture() {
		String key = "t" + UUID.randomUUID().toString().substring(0, 8);
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		String subject = "cutover-" + key;
		jdbc.update("insert into tnn (id, tnn_key, name) values (?, ?, ?)", tenant, key, key);
		jdbc.update("insert into wrk_node (id, tnn_id, node_key, kind, name, path) values (?, ?, 'root', 'ROOT', 'Root', array[?]::uuid[])", root, tenant, root);
		jdbc.update("insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'common', 'COMMON', 'Common', array[?, ?]::uuid[])", common, tenant, root, root, common);
		jdbc.update("insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'team', 'WORK', 'Team', array[?, ?]::uuid[])", workspace, tenant, root, root, workspace);
		jdbc.update("insert into org_unit (id, tnn_id, org_unit_key, name) values (?, ?, 'team', 'Team')", team, tenant);
		jdbc.update("insert into org_unit_mbr (id, tnn_id, org_unit_id, subj, rank) values (?, ?, ?, ?, 'S')", UUID.randomUUID(), tenant, team, subject);
		return new Fixture(tenant, common, workspace, team, subject);
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}

	private UUID thread(String kind, UUID directOwner, UUID workspace) {
		UUID id = UUID.randomUUID();
		UUID creator = directOwner == null ? user("creator-" + id) : directOwner;
		jdbc.update("insert into thr (id, kind, created_user_id, drc_own_user_id, title, wrk_node_id) values (?, ?, ?, ?, 't', ?)", id, kind, creator, directOwner, workspace);
		return id;
	}

	private void member(UUID thread, UUID user) {
		jdbc.update("insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id) values (?, ?, ?, 'OWNER', ?)", UUID.randomUUID(), thread, user, user);
	}

	private void grant(UUID tenant, UUID team, UUID workspace) {
		jdbc.update("insert into wrk_grn (id, tnn_id, org_unit_id, wrk_node_id, role) values (?, ?, ?, ?, 'VIEWER')", UUID.randomUUID(), tenant, team, workspace);
	}

	private record Fixture(UUID tenant, UUID common, UUID teamWorkspace, UUID team, String subject) {
	}
}
