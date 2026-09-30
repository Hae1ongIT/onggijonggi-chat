package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Class Name : DirectManageAuthorizerTest.java
 * Description : 감사 조회의 직접 MANAGE 판정(#259)을 검증한다 — 스위치와 무관하게 DB 부여로 계산하고, ADMIN 역할만 MANAGE를
 *               준다, 팀 규칙은 내 팀만, 직급 규칙은 팀이 없거나 내 팀이면서 서열이 닿을 때만, 부모의 부여는 보지 않는다,
 *               노드·Tenant·팀이 비활성이거나 배정이 없거나 다른 Tenant면 거부한다.
 */
class DirectManageAuthorizerTest {

	private static final String SUBJECT = "sub-kim";

	private final Tenant acme = new Tenant("acme", "ACME", TenantStatus.ACTIVE);
	private final UUID tenantId = acme.getId();
	private final OrgUnit team = new OrgUnit(tenantId, "hr", "인사팀", OrgUnitStatus.ACTIVE);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final WorkspaceGrantRepository workspaceGrants = mock(WorkspaceGrantRepository.class);
	private final RankGrantRepository rankGrants = mock(RankGrantRepository.class);
	private final DirectManageAuthorizer authorizer =
			new DirectManageAuthorizer(members, tenants, orgUnits, workspaceGrants, rankGrants);

	private WorkspaceNode root;
	private WorkspaceNode hr;
	private WorkspaceNode payroll;

	@BeforeEach
	void setUp() {
		root = WorkspaceNode.root(tenantId, "Root");
		hr = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀",
				WorkspaceNodeStatus.ACTIVE);
		payroll = WorkspaceNode.child(tenantId, hr.getId(), hr.getPath(), "payroll", WorkspaceNodeKind.ORG, "급여",
				WorkspaceNodeStatus.ACTIVE);
		when(tenants.findById(tenantId)).thenReturn(Optional.of(acme));
		when(orgUnits.findById(team.getId())).thenReturn(Optional.of(team));
		when(workspaceGrants.findByWorkspaceNodeId(any())).thenReturn(List.of());
		when(rankGrants.findByWorkspaceNodeId(any())).thenReturn(List.of());
		assign(Rank.K);
	}

	@Test
	void anAdminGrantToMyTeamGivesManage() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();
	}

	@Test
	void viewerAndContributorGrantsDoNotGiveManage() {
		grant(hr, team.getId(), WorkspaceRole.VIEWER);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		grant(hr, team.getId(), WorkspaceRole.CONTRIBUTOR);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void anAdminGrantToAnotherTeamDoesNotCount() {
		grant(hr, UUID.randomUUID(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aTeamlessRankRuleCountsWhenMyRankReachesIt() {
		// 과장(서열 4)은 "과장 이상(<= 4)" 규칙을 통과하고 "부장 이상(<= 2)" 규칙은 통과하지 못한다.
		rankRule(hr, null, Rank.K, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();

		rankRule(hr, null, Rank.B, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aTeamRankRuleCountsOnlyForMyTeam() {
		rankRule(hr, team.getId(), Rank.D, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();

		rankRule(hr, UUID.randomUUID(), Rank.D, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aRankRuleBelowAdminDoesNotGiveManage() {
		rankRule(hr, null, Rank.S, WorkspaceRole.CONTRIBUTOR);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aParentAdminGrantDoesNotReachTheChild() {
		// 권한은 상속되지 않는다(#299). 부모 노드에만 ADMIN이 있으면 자식의 감사는 볼 수 없다.
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, payroll)).isFalse();
	}

	@Test
	void inactiveNodeTenantOrTeamIsDenied() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		WorkspaceNode closedNode = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "old", WorkspaceNodeKind.ORG, "옛 팀",
				WorkspaceNodeStatus.INACTIVE);
		when(workspaceGrants.findByWorkspaceNodeId(closedNode.getId()))
				.thenReturn(List.of(new WorkspaceGrant(tenantId, team.getId(), closedNode.getId(), WorkspaceRole.ADMIN)));
		assertThat(authorizer.hasDirectManage(SUBJECT, closedNode)).isFalse();

		when(tenants.findById(tenantId)).thenReturn(Optional.of(new Tenant("acme", "ACME", TenantStatus.INACTIVE)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		when(tenants.findById(tenantId)).thenReturn(Optional.of(acme));
		when(orgUnits.findById(team.getId())).thenReturn(Optional.of(new OrgUnit(tenantId, "hr", "인사팀", OrgUnitStatus.INACTIVE)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void noAssignmentOrAnAssignmentInAnotherTenantIsDenied() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		when(members.findBySubject(SUBJECT)).thenReturn(List.of());
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		when(members.findBySubject(SUBJECT))
				.thenReturn(List.of(new OrgUnitMember(UUID.randomUUID(), team.getId(), SUBJECT, Rank.TL)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	private void assign(Rank rank) {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, team.getId(), SUBJECT, rank)));
	}

	private void grant(WorkspaceNode node, UUID orgUnitId, WorkspaceRole role) {
		when(workspaceGrants.findByWorkspaceNodeId(node.getId()))
				.thenReturn(List.of(new WorkspaceGrant(tenantId, orgUnitId, node.getId(), role)));
	}

	private void rankRule(WorkspaceNode node, UUID orgUnitId, Rank rank, WorkspaceRole role) {
		when(rankGrants.findByWorkspaceNodeId(node.getId()))
				.thenReturn(List.of(new RankGrant(tenantId, node.getId(), orgUnitId, rank, role)));
	}
}
