package com.onggijonggi.api.authz;

import com.onggijonggi.api.chat.RoomSessionRegistry;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : RbacPolicyRefresh.java
 * Description : 03·CORE 커밋된 Workspace·부여 변경(#260)을 Casbin에 반영하고 영향받는 협업방 구독을 해제한다.
 *               반영이 실패해도 DB 변경은 되돌리지 않는다. 대신 그 Tenant를 막아 두고(판정이 켜진 동안 판정·관리 쓰기 거부)
 *               1초부터 최대 30초 간격으로 다시 시도한다. 구독 해제는 판정이 켜진 뒤(절체 후)에만 한다 — 꺼진 동안은 기존
 *               채팅·WebSocket 동작을 바꾸지 않는다. Casbin 반영은 스위치와 무관하게 한다(casbin 프로필 안에서 스위치만 꺼 둔
 *               절체 전 운영에서도 규칙이 최신이어야 켜는 순간 맞는 판정을 한다).
 */
@Component
public class RbacPolicyRefresh {
	private static final Logger log = LoggerFactory.getLogger(RbacPolicyRefresh.class);
	private final CasbinRuleLoader loader;
	private final RbacProperties properties;
	private final ThrRepository threads;
	private final RoomSessionRegistry rooms;
	private final Clock clock;
	private final Map<UUID, Retry> blocked = new HashMap<>();

	private record Retry(int attempt, Instant due) { }

	@Autowired
	public RbacPolicyRefresh(CasbinRuleLoader loader, RbacProperties properties, ThrRepository threads,
			RoomSessionRegistry rooms) {
		this(loader, properties, threads, rooms, Clock.systemUTC());
	}

	RbacPolicyRefresh(CasbinRuleLoader loader, RbacProperties properties, ThrRepository threads,
			RoomSessionRegistry rooms, Clock clock) {
		this.loader = loader;
		this.properties = properties;
		this.threads = threads;
		this.rooms = rooms;
		this.clock = clock;
	}

	public synchronized boolean isBlocked(UUID tenantId) {
		return blocked.containsKey(tenantId);
	}

	/** 쓰기 트랜잭션이 커밋된 뒤에만 부른다. 롤백된 변경은 반영하지 않는다. */
	public void publish(UUID tenantId, Set<UUID> affectedNodes, UUID movedThreadId) {
		synchronized (this) {
			blocked.put(tenantId, new Retry(0, clock.instant()));
			tryReload(tenantId);
		}
		if (!properties.isEnforce()) return;
		if (movedThreadId != null) rooms.evictCollabRoom(movedThreadId);
		for (UUID nodeId : affectedNodes) {
			for (Thr thread : threads.findByWorkspaceNodeIdAndKind(nodeId, ThrKind.COLLAB)) {
				rooms.evictCollabRoom(thread.getId());
			}
		}
	}

	@Scheduled(fixedDelay = 1000)
	public synchronized void retryFailed() {
		for (UUID tenantId : Set.copyOf(blocked.keySet())) {
			Retry retry = blocked.get(tenantId);
			if (retry != null && !retry.due().isAfter(clock.instant())) tryReload(tenantId);
		}
	}

	private void tryReload(UUID tenantId) {
		try {
			loader.reload();
			blocked.remove(tenantId);
		} catch (RuntimeException error) {
			Retry previous = blocked.get(tenantId);
			int next = previous == null ? 1 : previous.attempt() + 1;
			long seconds = 1L << Math.min(next - 1, 5);
			blocked.put(tenantId, new Retry(next, clock.instant().plusSeconds(Math.min(seconds, 30))));
			log.error("커밋된 권한 변경을 Casbin에 반영하지 못했다 — 반영될 때까지 Tenant {}를 막는다", tenantId, error);
		}
	}
}
