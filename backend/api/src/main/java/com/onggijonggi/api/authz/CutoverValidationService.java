package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : CutoverValidationService.java
 * Description : 점검창에서 다시 실행하는 읽기 전용 단일 Tenant·최종 Workspace 사전 검증.
 *               Keycloak은 활성 subject만 제공하고 현재 소속·직급·권한의 정본은 DB다.
 */
@Service
public class CutoverValidationService {

	private final AppUserRepository users;
	private final OrgUnitMemberRepository members;
	private final OrgUnitRepository orgUnits;
	private final TenantRepository tenants;
	private final WorkspaceNodeRepository nodes;
	private final WorkspaceGrantRepository grants;
	private final RankGrantRepository rankGrants;
	private final JdbcTemplate jdbc;
	/** 응답이 끝없이 커지지 않게 하는 상한. 넘으면 FAILURES_TRUNCATED가 붙는다. */
	private static final int MAX_FAILURES = 500;

	public CutoverValidationService(AppUserRepository users, OrgUnitMemberRepository members,
			OrgUnitRepository orgUnits, TenantRepository tenants, WorkspaceNodeRepository nodes,
			WorkspaceGrantRepository grants, RankGrantRepository rankGrants, JdbcTemplate jdbc) {
		this.users = users;
		this.members = members;
		this.orgUnits = orgUnits;
		this.tenants = tenants;
		this.nodes = nodes;
		this.grants = grants;
		this.rankGrants = rankGrants;
		this.jdbc = jdbc;
	}

	/** Keycloak에서 활성인 subject만 받는다. app_user의 로컬 INACTIVE는 재활성화 가능하므로 제외 근거가 아니다. */
	@Transactional(readOnly = true)
	public CutoverValidationResult validate(Collection<String> enabledSubjects) {
		// 같은 (코드, Thread, subject)는 한 번만 담는다. 상한을 넘으면 나머지는 버리고 표시만 남긴다.
		Set<CutoverValidationResult.Failure> failures = new LinkedHashSet<>();
		List<Tenant> activeTenants = tenants.findAll().stream()
				.filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).toList();
		if (activeTenants.size() != 1) {
			failures.add(failure("ACTIVE_TENANT_COUNT", null, null));
			return new CutoverValidationResult(null, List.copyOf(failures));
		}
		UUID tenantId = activeTenants.get(0).getId();
		Map<UUID, WorkspaceNode> nodeById = new HashMap<>();
		for (WorkspaceNode node : nodes.findAll()) nodeById.put(node.getId(), node);
		List<WorkspaceNode> commonNodes = nodeById.values().stream()
				.filter(node -> node.getTenantId().equals(tenantId) && node.getKind() == WorkspaceNodeKind.COMMON
						&& node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.filter(node -> {
					WorkspaceNode parent = nodeById.get(node.getParentId());
					return parent != null && parent.getKind() == WorkspaceNodeKind.ROOT
							&& parent.getStatus() == WorkspaceNodeStatus.ACTIVE;
				}).toList();
		UUID commonId = commonNodes.size() == 1 ? commonNodes.get(0).getId() : null;
		if (commonId == null) failures.add(failure("ACTIVE_COMMON_REQUIRED", null, null));

		Set<String> enabled = new HashSet<>(enabledSubjects);
		Map<UUID, String> subjectByUser = new HashMap<>();
		for (AppUser user : users.findAll()) subjectByUser.put(user.getId(), user.getKeycloakSubj());
		Map<UUID, OrgUnit> unitById = new HashMap<>();
		for (OrgUnit unit : orgUnits.findAll()) unitById.put(unit.getId(), unit);
		Map<String, OrgUnitMember> assignmentBySubject = new HashMap<>();
		for (OrgUnitMember member : members.findAll()) assignmentBySubject.put(member.getSubject(), member);
		for (String subject : enabled) {
			OrgUnitMember member = assignmentBySubject.get(subject);
			OrgUnit unit = member == null ? null : unitById.get(member.getOrgUnitId());
			if (member == null || !tenantId.equals(member.getTenantId()) || unit == null
					|| !tenantId.equals(unit.getTenantId()) || unit.getStatus() != OrgUnitStatus.ACTIVE) {
				failures.add(failure("INVALID_ACTIVE_SUBJECT_ASSIGNMENT", null, subject));
			}
		}
		Map<UUID, List<WorkspaceGrant>> grantsByNode = new HashMap<>();
		for (WorkspaceGrant grant : grants.findAll())
			grantsByNode.computeIfAbsent(grant.getWorkspaceNodeId(), ignored -> new ArrayList<>()).add(grant);
		Map<UUID, List<RankGrant>> ranksByNode = new HashMap<>();
		for (RankGrant grant : rankGrants.findAll())
			ranksByNode.computeIfAbsent(grant.getWorkspaceNodeId(), ignored -> new ArrayList<>()).add(grant);

		List<ThreadRow> threads = jdbc.query("select id, kind, drc_own_user_id, tnn_id, wrk_node_id from thr order by id",
				(rows, index) -> new ThreadRow(rows.getObject(1, UUID.class), rows.getString(2),
						rows.getObject(3, UUID.class), rows.getObject(4, UUID.class), rows.getObject(5, UUID.class)));
		Map<UUID, ThreadRow> threadById = new HashMap<>();
		for (ThreadRow thread : threads) threadById.put(thread.id(), thread);
		List<ParticipantRow> participants = jdbc.query(
				"select thr_id, user_id, role from thr_mbr where status = 'ACTIVE' order by thr_id, user_id",
				(rows, index) -> new ParticipantRow(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3)));
		Set<String> ownerMemberships = new HashSet<>();
		for (ParticipantRow participant : participants)
			if ("OWNER".equals(participant.role())) ownerMemberships.add(participant.threadId() + ":" + participant.userId());
		for (ThreadRow thread : threads) {
			if (thread.tenantId() != null && !tenantId.equals(thread.tenantId()))
				failures.add(failure("THREAD_TENANT_MISMATCH", thread.id(), null));
			UUID finalNodeId = thread.workspaceId() == null ? commonId : thread.workspaceId();
			WorkspaceNode finalNode = nodeById.get(finalNodeId);
			if ("DIRECT".equals(thread.kind()) && thread.workspaceId() != null
					&& !thread.workspaceId().equals(commonId))
				failures.add(failure("DIRECT_OUTSIDE_COMMON", thread.id(), null));
			if (finalNode == null || !tenantId.equals(finalNode.getTenantId())
					|| finalNode.getStatus() != WorkspaceNodeStatus.ACTIVE
					|| finalNode.getKind() == WorkspaceNodeKind.ROOT)
				failures.add(failure("INVALID_THREAD_WORKSPACE", thread.id(), null));
			if ("DIRECT".equals(thread.kind())) {
				if (!ownerMemberships.contains(thread.id() + ":" + thread.directOwner()))
					failures.add(failure("DIRECT_OWNER_MEMBERSHIP_MISSING", thread.id(), null));
				checkActor(thread.id(), thread.directOwner(), finalNodeId, tenantId, enabled, subjectByUser,
						assignmentBySubject, unitById, grantsByNode, ranksByNode, failures);
			}
		}
		for (ParticipantRow participant : participants) {
			UUID threadId = participant.threadId();
			ThreadRow thread = threadById.get(threadId);
			if (thread != null) {
				if ("DIRECT".equals(thread.kind()) && participant.userId().equals(thread.directOwner())) continue;
				UUID finalNodeId = thread.workspaceId() == null ? commonId : thread.workspaceId();
				checkActor(threadId, participant.userId(), finalNodeId, tenantId, enabled, subjectByUser,
						assignmentBySubject, unitById, grantsByNode, ranksByNode, failures);
			}
		}
		// 재시도 가능한 키와 PENDING 초대의 행위자도 현재 Tenant를 벗어나면 절체를 멈춘다.
		for (ActorRow actor : jdbc.query("""
				select thr_id, user_id from thr_idm_key where created_at >= now() - interval '24 hours'
				union all select thr_id, user_id from msg_idm_key where created_at >= now() - interval '5 minutes'
				union all select thr_id, created_by_user_id from thr_inv where status = 'PENDING'
				""", (rows, index) -> new ActorRow(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)))) {
			ThreadRow thread = threadById.get(actor.threadId());
			if (thread == null) continue;
			UUID finalNodeId = thread.workspaceId() == null ? commonId : thread.workspaceId();
			checkActor(actor.threadId(), actor.userId(), finalNodeId, tenantId, enabled, subjectByUser,
					assignmentBySubject, unitById, grantsByNode, ranksByNode, failures);
		}
		List<CutoverValidationResult.Failure> reported = new ArrayList<>(failures.stream().limit(MAX_FAILURES).toList());
		if (failures.size() > MAX_FAILURES) reported.add(failure("FAILURES_TRUNCATED", null, null));
		return new CutoverValidationResult(tenantId, List.copyOf(reported));
	}

	private static void checkActor(UUID threadId, UUID userId, UUID nodeId, UUID tenantId, Set<String> enabled,
			Map<UUID, String> subjectByUser, Map<String, OrgUnitMember> assignments, Map<UUID, OrgUnit> units,
			Map<UUID, List<WorkspaceGrant>> grants, Map<UUID, List<RankGrant>> ranks,
			Set<CutoverValidationResult.Failure> failures) {
		String subject = subjectByUser.get(userId);
		if (subject == null) {
			failures.add(failure("UNKNOWN_THREAD_ACTOR", threadId, null));
			return;
		}
		if (!enabled.contains(subject)) return; // 삭제·비활성 계정의 참여 이력은 보존한다.
		OrgUnitMember member = assignments.get(subject);
		OrgUnit unit = member == null ? null : units.get(member.getOrgUnitId());
		if (member == null || unit == null || !tenantId.equals(member.getTenantId())
				|| !tenantId.equals(unit.getTenantId()) || unit.getStatus() != OrgUnitStatus.ACTIVE) {
			failures.add(failure("THREAD_ACTOR_TENANT_MISMATCH", threadId, subject));
			return;
		}
		boolean direct = grants.getOrDefault(nodeId, List.of()).stream()
				.anyMatch(grant -> tenantId.equals(grant.getTenantId())
						&& member.getOrgUnitId().equals(grant.getOrgUnitId())
						&& CasbinPolicy.roleAllows(grant.getRole(), CasbinPolicy.VIEW));
		boolean rank = ranks.getOrDefault(nodeId, List.of()).stream()
				.anyMatch(rule -> tenantId.equals(rule.getTenantId())
						&& (rule.getOrgUnitId() == null || member.getOrgUnitId().equals(rule.getOrgUnitId()))
						&& member.getRank().order() <= rule.getRank().order()
						&& CasbinPolicy.roleAllows(rule.getRole(), CasbinPolicy.VIEW));
		if (!direct && !rank) failures.add(failure("THREAD_ACTOR_VIEW_MISSING", threadId, subject));
	}

	private static CutoverValidationResult.Failure failure(String code, UUID threadId, String subject) {
		return new CutoverValidationResult.Failure(code, threadId, subject);
	}

	private record ThreadRow(UUID id, String kind, UUID directOwner, UUID tenantId, UUID workspaceId) {
	}

	private record ParticipantRow(UUID threadId, UUID userId, String role) {
	}

	private record ActorRow(UUID threadId, UUID userId) {
	}
}
