package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Class Name : DirectManageAuthorizer.java
 * Description : 03·CORE "이 사람이 이 workspace에 직접 MANAGE를 갖나"를 판정 스위치와 무관하게 엄격히 본다(#259 감사 조회).
 *               WorkspaceAuthorizer는 app.rbac.enforce가 꺼지면 늘 허용하고, 스위치를 켜는 casbin 프로필이 꺼지면
 *               Casbin 서버 자체가 없다. 그래서 Casbin을 거치지 않고 DB의 부여에서 직접 계산한다.
 *               선검증은 WorkspaceAuthorizer와 같다: 노드·Tenant ACTIVE, 배정이 노드와 같은 Tenant에 있고 그 org-unit이 ACTIVE.
 *               판정은 Casbin 규칙과 뜻이 같아야 한다(CasbinPolicy.rules) — 역할→액션은 CasbinPolicy.roleAllows를 쓰고,
 *               직급 규칙은 "팀이 없거나 내 팀이면서 내 서열 숫자 <= 규칙 서열"이다. 부모 노드의 부여는 보지 않는다(상속 없음).
 *               DB가 블로킹이라 호출하는 쪽이 boundedElastic에서 부른다.
 */
@Service
public class DirectManageAuthorizer {

	private final OrgUnitMemberRepository members;
	private final TenantRepository tenants;
	private final OrgUnitRepository orgUnits;
	private final WorkspaceGrantRepository workspaceGrants;
	private final RankGrantRepository rankGrants;

	public DirectManageAuthorizer(OrgUnitMemberRepository members, TenantRepository tenants, OrgUnitRepository orgUnits,
			WorkspaceGrantRepository workspaceGrants, RankGrantRepository rankGrants) {
		this.members = members;
		this.tenants = tenants;
		this.orgUnits = orgUnits;
		this.workspaceGrants = workspaceGrants;
		this.rankGrants = rankGrants;
	}

	public boolean hasDirectManage(String subject, WorkspaceNode node) {
		if (node.getStatus() != WorkspaceNodeStatus.ACTIVE) return false;
		if (tenants.findById(node.getTenantId()).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).isEmpty()) {
			return false;
		}
		List<OrgUnitMember> assignments = members.findBySubject(subject).stream()
				.filter(assignment -> node.getTenantId().equals(assignment.getTenantId()) && isActiveOrgUnit(assignment))
				.toList();
		if (assignments.isEmpty()) return false;
		List<WorkspaceGrant> grants = workspaceGrants.findByWorkspaceNodeId(node.getId());
		List<RankGrant> rankRules = rankGrants.findByWorkspaceNodeId(node.getId());
		return assignments.stream().anyMatch(assignment -> grants.stream().anyMatch(grant -> allows(grant, assignment))
				|| rankRules.stream().anyMatch(rule -> allows(rule, assignment)));
	}

	private boolean isActiveOrgUnit(OrgUnitMember assignment) {
		return orgUnits.findById(assignment.getOrgUnitId())
				.filter(unit -> unit.getTenantId().equals(assignment.getTenantId()) && unit.getStatus() == OrgUnitStatus.ACTIVE)
				.isPresent();
	}

	private static boolean allows(WorkspaceGrant grant, OrgUnitMember assignment) {
		return grant.getOrgUnitId().equals(assignment.getOrgUnitId())
				&& CasbinPolicy.roleAllows(grant.getRole(), CasbinPolicy.MANAGE);
	}

	/** Casbin 식 `r.sub.OrgUnit == '<팀>' && r.sub.Rank <= N`(팀이 없으면 서열 조건만)과 같다. */
	private static boolean allows(RankGrant rule, OrgUnitMember assignment) {
		return (rule.getOrgUnitId() == null || rule.getOrgUnitId().equals(assignment.getOrgUnitId()))
				&& assignment.getRank().order() <= rule.getRank().order()
				&& CasbinPolicy.roleAllows(rule.getRole(), CasbinPolicy.MANAGE);
	}
}
