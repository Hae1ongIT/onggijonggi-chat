package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.authz.WorkspaceRole;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WorkspaceManagementService.java
 * Description : 03·CORE Workspace 노드·부여·org-unit 변경과 협업방 이동(#260). 연산마다 Tenant 행을 먼저 잠그고
 *               (같은 Tenant의 권한 변경은 직렬), DB 변경과 authz_adt 감사를 한 트랜잭션에서 함께 성공·롤백한다.
 *               커밋 뒤 RbacPolicyRefresh로 Casbin 반영과 구독 해제를 한다. 배포 설정이 선언한 노드·부여·org-unit은 바꾸지 않는다
 *               (바꾸면 다음 bootstrap이 drift로 Tenant를 막는다). 권한은 DirectManageAuthorizer로 대상 노드마다 직접 본다.
 */
@Service
public class WorkspaceManagementService {
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,62}");
	/** wrk_node_guard의 깊이 제한(path 원소 수 11 이하)과 같다. 넘기면 DB가 거부해 500이 되므로 먼저 409로 막는다. */
	private static final int MAX_DEPTH = 11;
	private final TenantRepository tenants;
	private final WorkspaceNodeRepository nodes;
	private final WorkspaceGrantRepository grants;
	private final OrgUnitRepository orgUnits;
	private final OrgUnitMemberRepository members;
	private final AppUserRepository users;
	private final ThrRepository threads;
	private final AuthorizationAuditRepository audits;
	private final DirectManageAuthorizer manage;
	private final RbacBootstrapConfigReader config;
	private final RbacPolicyRefresh refresh;
	private final EntityManager entityManager;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	/**
	 * 관리 연산의 행위자. requestId는 한 요청이 남긴 감사 행을 묶고, traceId는 응답의 X-Trace-Id와 같아 오류·로그와 감사를
	 * 이어 준다(TraceIdWebFilter가 발급한다).
	 */
	public record Actor(UUID userId, String subject, List<String> roles, String requestId, String traceId) {
		/** 요청 밖(테스트 등)에서 부를 때. 추적 ID는 없다. */
		public Actor(UUID userId, String subject, List<String> roles, String requestId) {
			this(userId, subject, roles, requestId, null);
		}
	}

	public WorkspaceManagementService(TenantRepository tenants, WorkspaceNodeRepository nodes,
			WorkspaceGrantRepository grants, OrgUnitRepository orgUnits, OrgUnitMemberRepository members,
			AppUserRepository users, ThrRepository threads, AuthorizationAuditRepository audits,
			DirectManageAuthorizer manage, RbacBootstrapConfigReader config, RbacPolicyRefresh refresh,
			EntityManager entityManager, ObjectMapper json, PlatformTransactionManager transactionManager) {
		this.tenants = tenants;
		this.nodes = nodes;
		this.grants = grants;
		this.orgUnits = orgUnits;
		this.members = members;
		this.users = users;
		this.threads = threads;
		this.audits = audits;
		this.manage = manage;
		this.config = config;
		this.refresh = refresh;
		this.entityManager = entityManager;
		this.json = json;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public UUID createNode(Actor actor, UUID parentId, String key, WorkspaceNodeKind kind, String name) {
		validKey(key);
		if (key.equals("root") || key.equals("common")) throw badRequest();
		validName(name);
		if (kind != WorkspaceNodeKind.ORG && kind != WorkspaceNodeKind.WORK) throw badRequest();
		UUID tenantId = tenantIdOf(required(parentId));
		UUID result = transactions.execute(status -> {
			lock(tenantId);
			WorkspaceNode parent = node(parentId, tenantId);
			manage.require(actor.subject(), parent);
			if (parent.getKind() == WorkspaceNodeKind.COMMON || parent.getPath().length + 1 > MAX_DEPTH) throw conflict();
			if (nodes.findByTenantIdAndKey(tenantId, key).isPresent()) throw conflict();
			uniqueSiblingName(parentId, name, null);
			WorkspaceNode created = nodes.saveAndFlush(WorkspaceNode.child(tenantId, parentId, parent.getPath(), key,
					kind, name, WorkspaceNodeStatus.ACTIVE));
			UUID actorOrgUnit = members.findBySubject(actor.subject()).stream()
					.filter(member -> tenantId.equals(member.getTenantId()))
					.map(member -> member.getOrgUnitId())
					.filter(id -> orgUnits.findById(id).filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE).isPresent())
					.findFirst().orElseThrow(WorkspaceManagementService::forbidden);
			WorkspaceGrant initial = grants.saveAndFlush(new WorkspaceGrant(tenantId, actorOrgUnit, created.getId(), WorkspaceRole.ADMIN));
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_CREATED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(created), created.getId(), null, nodeSnapshot(created));
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(initial), created.getId(), null, grantSnapshot(initial));
			return created.getId();
		});
		refresh.publish(tenantId, Set.of(result), null);
		return result;
	}

	public void renameNode(Actor actor, UUID nodeId, String name) {
		validName(name);
		UUID tenantId = tenantIdOf(nodeId);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			manage.require(actor.subject(), current);
			undeclaredNode(declared(tenant), current);
			uniqueSiblingName(current.getParentId(), name, nodeId);
			Map<String, Object> before = nodeSnapshot(current);
			current.rename(name);
			nodes.saveAndFlush(current);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_RENAMED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, nodeSnapshot(current));
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void deactivateSubtree(Actor actor, UUID nodeId) {
		UUID tenantId = tenantIdOf(nodeId);
		Set<UUID> affected = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode target = node(nodeId, tenantId);
			// 권한부터 본다 — 상태·종류를 먼저 보면 권한 없는 사람도 409/403 차이로 노드 상태를 알 수 있다.
			manage.requireIgnoringStatus(actor.subject(), target);
			if (target.getKind() == WorkspaceNodeKind.ROOT || target.getKind() == WorkspaceNodeKind.COMMON
					|| target.getStatus() != WorkspaceNodeStatus.ACTIVE) throw conflict();
			List<WorkspaceNode> subtree = nodes.findByTenantId(tenantId).stream()
					.filter(candidate -> candidate.getStatus() == WorkspaceNodeStatus.ACTIVE
							&& Arrays.asList(candidate.getPath()).contains(nodeId))
					.sorted(Comparator.comparingInt((WorkspaceNode candidate) -> candidate.getPath().length).reversed()).toList();
			Optional<RbacBootstrapSpec.TenantSpec> declared = declared(tenant);
			for (WorkspaceNode current : subtree) {
				manage.require(actor.subject(), current);
				undeclaredNode(declared, current);
				if (threads.existsByWorkspaceNodeId(current.getId())) throw conflict();
			}
			for (WorkspaceNode current : subtree) {
				Map<String, Object> before = nodeSnapshot(current);
				current.reconcileStatus(WorkspaceNodeStatus.INACTIVE);
				nodes.saveAndFlush(current);
				audit(actor, tenantId, AuthorizationAuditEventKind.NODE_DEACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
						nodeRef(current), current.getId(), before, nodeSnapshot(current));
			}
			return subtree.stream().map(WorkspaceNode::getId).collect(Collectors.toSet());
		});
		refresh.publish(tenantId, affected, null);
	}

	public void reactivateNode(Actor actor, UUID nodeId) {
		UUID tenantId = tenantIdOf(nodeId);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			// 권한부터 본다. 비활성 노드 자체의 보존된 직접 부여로 판정하고, 부모의 MANAGE로 대신하지 않는다(상속 없음).
			manage.requireIgnoringStatus(actor.subject(), current);
			if (current.getStatus() != WorkspaceNodeStatus.INACTIVE
					|| current.getKind() == WorkspaceNodeKind.ROOT || current.getKind() == WorkspaceNodeKind.COMMON) throw conflict();
			WorkspaceNode parent = node(current.getParentId(), tenantId);
			if (parent.getStatus() != WorkspaceNodeStatus.ACTIVE) throw conflict();
			undeclaredNode(declared(tenant), current);
			uniqueSiblingName(parent.getId(), current.getName(), nodeId);
			Map<String, Object> before = nodeSnapshot(current);
			current.reconcileStatus(WorkspaceNodeStatus.ACTIVE);
			nodes.saveAndFlush(current);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_REACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, nodeSnapshot(current));
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void reparentLeaf(Actor actor, UUID nodeId, UUID newParentId) {
		required(newParentId);
		UUID tenantId = tenantIdOf(nodeId);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			// 권한부터 본다(세 노드 모두 직접 MANAGE). ROOT에는 부여가 없으므로 ROOT가 어느 쪽 부모든 여기서 403이다.
			manage.require(actor.subject(), current);
			WorkspaceNode oldParent = node(current.getParentId(), tenantId);
			WorkspaceNode newParent = node(newParentId, tenantId);
			manage.require(actor.subject(), oldParent);
			manage.require(actor.subject(), newParent);
			if (oldParent.getKind() == WorkspaceNodeKind.ROOT || newParent.getKind() == WorkspaceNodeKind.ROOT
					|| newParent.getKind() == WorkspaceNodeKind.COMMON || current.getStatus() != WorkspaceNodeStatus.ACTIVE
					|| newParent.getStatus() != WorkspaceNodeStatus.ACTIVE || oldParent.getId().equals(newParentId)
					|| nodeId.equals(newParentId) || newParent.getPath().length + 1 > MAX_DEPTH
					|| nodes.existsByParentId(nodeId) || threads.existsByWorkspaceNodeId(nodeId)) throw conflict();
			Optional<RbacBootstrapSpec.TenantSpec> declared = declared(tenant);
			undeclaredNode(declared, current);
			uniqueSiblingName(newParentId, current.getName(), nodeId);
			List<WorkspaceGrant> previous = grants.findByWorkspaceNodeId(nodeId);
			for (WorkspaceGrant grant : previous) {
				undeclaredGrant(declared, tenantId, grant);
				if (orgUnits.findById(grant.getOrgUnitId())
						.filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE).isEmpty()) throw conflict();
			}
			if (previous.stream().noneMatch(grant -> grant.getRole() == WorkspaceRole.ADMIN)) throw conflict();
			Map<String, Object> before = nodeSnapshot(current);
			// DB guard는 부모를 바꾸는 UPDATE 순간 그 노드의 직접 부여가 0건이길 요구한다. 같은 트랜잭션에서 지웠다가 되살린다.
			entityManager.flush();
			for (WorkspaceGrant grant : previous) {
				entityManager.createNativeQuery("delete from wrk_grn where id = :id")
						.setParameter("id", grant.getId()).executeUpdate();
			}
			current.moveTo(newParentId, newParent.getPath());
			nodes.saveAndFlush(current);
			for (WorkspaceGrant grant : previous) {
				entityManager.createNativeQuery("insert into wrk_grn (id, tnn_id, org_unit_id, wrk_node_id, role, created_at, updated_at) "
						+ "values (:id, :tenant, :org, :node, :role, now(), now())")
						.setParameter("id", grant.getId()).setParameter("tenant", tenantId)
						.setParameter("org", grant.getOrgUnitId()).setParameter("node", nodeId)
						.setParameter("role", grant.getRole().name()).executeUpdate();
			}
			Map<String, Object> after = nodeSnapshot(current);
			// 부여는 같은 구성으로 되살렸으므로 전후가 같다 — 이동이 부여를 바꾸지 않았다는 기록이다.
			List<Map<String, Object>> restoredGrants = previous.stream().map(WorkspaceManagementService::grantSnapshot).toList();
			before.put("grants", restoredGrants);
			after.put("grants", restoredGrants);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_REPARENTED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, after);
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public UUID addGrant(Actor actor, UUID nodeId, UUID orgUnitId, WorkspaceRole role) {
		if (role == null) throw badRequest();
		required(orgUnitId);
		UUID tenantId = tenantIdOf(nodeId);
		UUID id = transactions.execute(status -> {
			lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			manage.require(actor.subject(), current);
			if (current.getKind() == WorkspaceNodeKind.ROOT) throw badRequest();
			if (orgUnits.findById(orgUnitId).filter(value -> tenantId.equals(value.getTenantId())
					&& value.getStatus() == OrgUnitStatus.ACTIVE).isEmpty()) throw conflict();
			if (hasGrant(nodeId, orgUnitId, role, null)) throw conflict();
			WorkspaceGrant grant = grants.saveAndFlush(new WorkspaceGrant(tenantId, orgUnitId, nodeId, role));
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), nodeId, null, grantSnapshot(grant));
			return grant.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
		return id;
	}

	public void changeGrantRole(Actor actor, UUID grantId, WorkspaceRole role) {
		if (role == null) throw badRequest();
		UUID tenantId = tenantIdOfGrant(grantId);
		UUID nodeId = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceGrant grant = grant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredGrant(declared(tenant), tenantId, grant);
			if (requiredCommonGrant(node, grant)) throw conflict();
			if (grant.getRole() == role) return node.getId();
			if (hasGrant(node.getId(), grant.getOrgUnitId(), role, grantId)) throw conflict();
			if (grant.getRole() == WorkspaceRole.ADMIN && role != WorkspaceRole.ADMIN) requireRemainingAdmin(node.getId(), grantId);
			Map<String, Object> before = grantSnapshot(grant);
			grant.changeRole(role);
			grants.saveAndFlush(grant);
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REPLACED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), node.getId(), before, grantSnapshot(grant));
			return node.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void removeGrant(Actor actor, UUID grantId) {
		UUID tenantId = tenantIdOfGrant(grantId);
		UUID nodeId = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceGrant grant = grant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredGrant(declared(tenant), tenantId, grant);
			if (requiredCommonGrant(node, grant)) throw conflict();
			if (grant.getRole() == WorkspaceRole.ADMIN) requireRemainingAdmin(node.getId(), grantId);
			Map<String, Object> before = grantSnapshot(grant);
			grants.delete(grant);
			grants.flush();
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REMOVED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), node.getId(), before, null);
			return node.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void moveCollabThread(Actor actor, UUID threadId, UUID destinationId) {
		required(destinationId);
		Thr existing = threads.findById(threadId).orElseThrow(WorkspaceManagementService::notFound);
		UUID tenantId = existing.getTenantId();
		if (tenantId == null) throw notFound();
		transactions.executeWithoutResult(status -> {
			lock(tenantId);
			Thr thread = threads.findById(threadId).orElseThrow(WorkspaceManagementService::notFound);
			if (thread.getKind() != ThrKind.COLLAB || !tenantId.equals(thread.getTenantId())
					|| thread.getWorkspaceNodeId() == null) throw notFound();
			WorkspaceNode source = node(thread.getWorkspaceNodeId(), tenantId);
			WorkspaceNode destination = node(destinationId, tenantId);
			manage.require(actor.subject(), source);
			manage.require(actor.subject(), destination);
			if (destination.getKind() == WorkspaceNodeKind.ROOT || source.getId().equals(destinationId)) throw conflict();
			thread.moveToWorkspace(destinationId);
			threads.saveAndFlush(thread);
			Map<String, Object> before = Map.of("thread_id", threadId, "wrk_node_id", source.getId());
			Map<String, Object> after = Map.of("thread_id", threadId, "wrk_node_id", destinationId);
			for (UUID nodeId : List.of(source.getId(), destinationId)) {
				audit(actor, tenantId, AuthorizationAuditEventKind.THREAD_MOVED, AuthorizationAuditTargetKind.THREAD,
						Map.of("thread_id", threadId), nodeId, before, after);
			}
		});
		refresh.publish(tenantId, Set.of(), threadId);
	}

	public UUID createOrgUnit(Actor actor, String tenantKey, String key, String name) {
		validKey(key);
		validName(name);
		UUID tenantId = tenants.findByKey(tenantKey).orElseThrow(WorkspaceManagementService::notFound).getId();
		UUID orgUnitId = transactions.execute(status -> {
			Tenant tenant = lock(tenantKey);
			undeclaredOrgUnit(declared(tenant), key);
			if (orgUnits.findByTenantIdAndKey(tenant.getId(), key).isPresent()) throw conflict();
			OrgUnit unit = orgUnits.saveAndFlush(new OrgUnit(tenant.getId(), key, name, OrgUnitStatus.ACTIVE));
			WorkspaceNode common = nodes.findByTenantIdAndKey(tenant.getId(), "common")
					.orElseThrow(WorkspaceManagementService::conflict);
			WorkspaceGrant grant = grants.saveAndFlush(new WorkspaceGrant(tenant.getId(), unit.getId(),
					common.getId(), WorkspaceRole.VIEWER));
			audit(actor, tenant.getId(), AuthorizationAuditEventKind.ORG_UNIT_CREATED, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, null, orgSnapshot(unit));
			audit(actor, tenant.getId(), AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), common.getId(), null, grantSnapshot(grant));
			return unit.getId();
		});
		refresh.publish(tenantId, Set.of(), null);
		return orgUnitId;
	}

	public void changeOrgUnit(Actor actor, String tenantKey, UUID orgUnitId, String name, OrgUnitStatus targetStatus) {
		// 상태 변경이 아니면 이름 변경이다 — 이름이 비면 400이다(그대로 두면 NOT NULL 위반이 500이 된다).
		if (targetStatus == null || name != null) validName(name);
		UUID tenantId = tenants.findByKey(tenantKey).orElseThrow(WorkspaceManagementService::notFound).getId();
		Set<UUID> affected = transactions.execute(status -> {
			Tenant tenant = lock(tenantKey);
			OrgUnit unit = orgUnits.findById(orgUnitId).filter(value -> value.getTenantId().equals(tenant.getId()))
					.orElseThrow(WorkspaceManagementService::notFound);
			undeclaredOrgUnit(declared(tenant), unit.getKey());
			// app_user가 없는 배정(아직 로그인하지 않은 사람)도 배정된 사용자로 센다 — 비활성화하면 그 사람이 막힌다.
			if (targetStatus == OrgUnitStatus.INACTIVE && members.findByOrgUnitId(unit.getId()).stream()
					.anyMatch(member -> users.findByKeycloakSubj(member.getSubject())
									.map(user -> user.getStatus() == AppUserStatus.ACTIVE).orElse(true))) throw conflict();
			Map<String, Object> before = orgSnapshot(unit);
			AuthorizationAuditEventKind event;
			if (targetStatus != null) {
				if (unit.getStatus() == targetStatus) throw conflict();
				unit.reconcileStatus(targetStatus);
				event = targetStatus == OrgUnitStatus.ACTIVE ? AuthorizationAuditEventKind.ORG_UNIT_REACTIVATED
						: AuthorizationAuditEventKind.ORG_UNIT_DEACTIVATED;
			} else {
				unit.rename(name);
				event = AuthorizationAuditEventKind.ORG_UNIT_RENAMED;
			}
			orgUnits.saveAndFlush(unit);
			audit(actor, tenant.getId(), event, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, before, orgSnapshot(unit));
			return grants.findByOrgUnitId(orgUnitId).stream().map(WorkspaceGrant::getWorkspaceNodeId).collect(Collectors.toSet());
		});
		refresh.publish(tenantId, affected, null);
	}

	private UUID tenantIdOf(UUID nodeId) { return nodes.findById(nodeId).orElseThrow(WorkspaceManagementService::notFound).getTenantId(); }
	private UUID tenantIdOfGrant(UUID id) { return grants.findById(id).orElseThrow(WorkspaceManagementService::notFound).getTenantId(); }
	private Tenant lock(UUID tenantId) {
		Tenant current = tenants.findById(tenantId).orElseThrow(WorkspaceManagementService::notFound);
		return lock(current.getKey());
	}
	private Tenant lock(String tenantKey) {
		return tenants.findByKeyForUpdate(tenantKey).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	private WorkspaceNode node(UUID id, UUID tenantId) {
		return nodes.findById(id).filter(value -> value.getTenantId().equals(tenantId))
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	private WorkspaceGrant grant(UUID id, UUID tenantId) {
		return grants.findById(id).filter(value -> value.getTenantId().equals(tenantId))
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	/**
	 * 배포 설정이 이 Tenant에 선언한 내용. 설정 파일을 읽고 파싱하므로 연산마다 한 번만 부르고 결과를 넘긴다 — 한 요청 안에서
	 * 파일이 바뀌어 판정이 갈리지도 않는다. 설정이 없거나 이 Tenant를 선언하지 않았으면 비어 있다.
	 */
	private Optional<RbacBootstrapSpec.TenantSpec> declared(Tenant tenant) {
		return config.loadWithFingerprint().stream().flatMap(loaded -> loaded.spec().tenants().stream())
				.filter(spec -> spec.key().equals(tenant.getKey())).findFirst();
	}
	private static void undeclaredNode(Optional<RbacBootstrapSpec.TenantSpec> declared, WorkspaceNode node) {
		if (declared.stream().flatMap(spec -> spec.nodes().stream()).anyMatch(spec -> spec.key().equals(node.getKey()))) {
			throw conflict();
		}
	}
	private static void undeclaredOrgUnit(Optional<RbacBootstrapSpec.TenantSpec> declared, String key) {
		if (declared.stream().flatMap(spec -> spec.orgUnits().stream()).anyMatch(spec -> spec.key().equals(key))) {
			throw conflict();
		}
	}
	private void undeclaredGrant(Optional<RbacBootstrapSpec.TenantSpec> declared, UUID tenantId, WorkspaceGrant grant) {
		if (declared.isEmpty()) return;
		String nodeKey = node(grant.getWorkspaceNodeId(), tenantId).getKey();
		String orgKey = orgUnits.findById(grant.getOrgUnitId()).orElseThrow(WorkspaceManagementService::conflict).getKey();
		if (declared.get().grants().stream().anyMatch(spec -> spec.node().equals(nodeKey) && spec.orgUnit().equals(orgKey)
				&& spec.role().equals(grant.getRole().name()))) throw conflict();
	}
	/** 같은 노드·org-unit·역할의 부여가 이미 있나(except는 자기 자신). 있으면 uq_wrk_grn_policy 위반이 500이 되므로 먼저 409로 막는다. */
	private boolean hasGrant(UUID nodeId, UUID orgUnitId, WorkspaceRole role, UUID except) {
		return grants.findByWorkspaceNodeId(nodeId).stream().anyMatch(grant -> grant.getOrgUnitId().equals(orgUnitId)
				&& grant.getRole() == role && !grant.getId().equals(except));
	}
	private void requireRemainingAdmin(UUID nodeId, UUID removedId) {
		if (grants.findByWorkspaceNodeId(nodeId).stream()
				.noneMatch(grant -> !grant.getId().equals(removedId) && grant.getRole() == WorkspaceRole.ADMIN)) throw conflict();
	}
	private boolean requiredCommonGrant(WorkspaceNode node, WorkspaceGrant grant) {
		return node.getKind() == WorkspaceNodeKind.COMMON && grant.getRole() == WorkspaceRole.VIEWER;
	}
	/** ACTIVE 형제끼리 이름이 대소문자 무시로 유일해야 한다(DB의 lower(name) 부분 유일 인덱스와 같다). */
	private void uniqueSiblingName(UUID parentId, String name, UUID except) {
		if (nodes.findByParentId(parentId).stream().anyMatch(candidate -> candidate.getStatus() == WorkspaceNodeStatus.ACTIVE
				&& !Objects.equals(candidate.getId(), except) && candidate.getName().equalsIgnoreCase(name))) throw conflict();
	}
	private void audit(Actor actor, UUID tenantId, AuthorizationAuditEventKind event, AuthorizationAuditTargetKind target,
			Map<String, Object> ref, UUID nodeId, Map<String, Object> before, Map<String, Object> after) {
		audits.save(AuthorizationAudit.managed(tenantId, actor.userId(), json.writeValueAsString(actor.roles()), event,
				target, json.writeValueAsString(ref), nodeId, before == null ? null : json.writeValueAsString(before),
				after == null ? null : json.writeValueAsString(after), actor.requestId(), actor.traceId()));
	}
	// 대상 참조와 스냅샷은 bootstrap(RbacBootstrapService)이 남기는 SYSTEM 행과 같은 키·모양으로 쓴다 — 감사 조회(#259)를 읽는
	// 쪽이 행위자가 SYSTEM이든 USER이든 같은 키로 읽게 하고, id만으로는 사람이 읽을 수 없어 key를 함께 남긴다.
	private static Map<String, Object> nodeRef(WorkspaceNode node) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_node_id", node.getId());
		ref.put("node_key", node.getKey());
		return ref;
	}
	private Map<String, Object> policyRef(WorkspaceGrant grant) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_grn_id", grant.getId());
		ref.put("org_unit_key", orgUnits.findById(grant.getOrgUnitId()).map(OrgUnit::getKey).orElse(null));
		ref.put("role", grant.getRole().name());
		ref.put("wrk_node_id", grant.getWorkspaceNodeId());
		return ref;
	}
	private static Map<String, Object> orgUnitRef(OrgUnit unit) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("org_unit_key", unit.getKey());
		return ref;
	}
	private static Map<String, Object> nodeSnapshot(WorkspaceNode node) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", node.getId());
		snapshot.put("node_key", node.getKey());
		snapshot.put("kind", node.getKind().name());
		snapshot.put("prn_id", node.getParentId());
		snapshot.put("name", node.getName());
		snapshot.put("status", node.getStatus().name());
		snapshot.put("path", Arrays.stream(node.getPath()).map(UUID::toString).toList());
		snapshot.put("inactive_at", node.getInactiveAt());
		return snapshot;
	}
	private static Map<String, Object> grantSnapshot(WorkspaceGrant grant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", grant.getId());
		snapshot.put("tnn_id", grant.getTenantId());
		snapshot.put("org_unit_id", grant.getOrgUnitId());
		snapshot.put("role", grant.getRole().name());
		snapshot.put("wrk_node_id", grant.getWorkspaceNodeId());
		return snapshot;
	}
	private static Map<String, Object> orgSnapshot(OrgUnit unit) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", unit.getId());
		snapshot.put("org_unit_key", unit.getKey());
		snapshot.put("name", unit.getName());
		snapshot.put("status", unit.getStatus().name());
		snapshot.put("inactive_at", unit.getInactiveAt());
		return snapshot;
	}
	private static void validKey(String value) {
		if (value == null || !KEY.matcher(value).matches()) throw badRequest();
	}
	private static void validName(String value) {
		if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.strip())
				|| value.chars().anyMatch(Character::isISOControl)) throw badRequest();
	}
	private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
	private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
	private static ResponseStatusException conflict() { return new RbacStateConflictException(); }
	/** 요청 본문의 필수 id가 비었으면 400이다 — 그대로 조회에 넘기면 IllegalArgumentException이 500이 된다. */
	private static UUID required(UUID id) {
		if (id == null) throw badRequest();
		return id;
	}
}
