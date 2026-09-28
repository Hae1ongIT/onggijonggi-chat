package com.onggijonggi.api.chat;

import org.springframework.stereotype.Component;

/**
 * Class Name : CollabAuthorizationRevoker.java
 * Description : 03·CORE 사람의 조직·직급이 바뀌거나 계정이 비활성화된 뒤, 그 사람이 들고 있던 협업방 권한을 바로 거둔다.
 *               협업방 구독을 모두 해제하고, 그 사람이 시작한 협업방 AI 턴(진행 중·대기 중)만 취소한다. 다시 구독하면
 *               최신 DB 권한으로 판정한다. 1:1(DIRECT)은 워크스페이스 소속이 아니라 소유자 계약으로 지키므로 건드리지 않는다.
 */
@Component
public class CollabAuthorizationRevoker {

	private final RoomSessionRegistry roomSessionRegistry;
	private final ThreadMessageDispatcher threadMessageDispatcher;

	public CollabAuthorizationRevoker(RoomSessionRegistry roomSessionRegistry,
			ThreadMessageDispatcher threadMessageDispatcher) {
		this.roomSessionRegistry = roomSessionRegistry;
		this.threadMessageDispatcher = threadMessageDispatcher;
	}

	public void revoke(String subject) {
		roomSessionRegistry.evictCollabSubscriptions(subject);
		threadMessageDispatcher.cancelCollabTurnsFrom(subject);
	}
}
