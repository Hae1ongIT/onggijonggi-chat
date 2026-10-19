package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChatAnswerFrame.java
 * Description : AI 응답 스트리밍(delta)·근거 인용(citations)·진행 상태(status)를 하나로 묶은
 *               프레임(이슈 #10 코멘트 확정 스펙). 기존 chat.token/chat.done 두 타입과 PR #46이
 *               제안 스펙으로 먼저 넣어둔 chat.citation을 이 타입 하나로 흡수한다.
 *
 *               delta·citations는 패킷마다 필요한 것만 채워서 온다 — 둘 다 비어 있는 패킷(예: 상태만
 *               알리는 패킷)도 유효하고, citations를 delta보다 먼저(delta="", status=STREAMING
 *               조합으로) 보낼 수도 있다 — 근거 패널이 답변 완료 전에 먼저 채워지는 UX를 지원하기
 *               위함이다.
 *
 *               restrictedResultsOmitted는 기존 REST /api/chat/citations 응답(CitationsResponse)에
 *               있던 RBAC 소프트 필터링 신호를 그대로 옮긴 것이다. citations와는 서로 독립적인
 *               필드다 — citations가 빈 배열이어도(전부 걸러진 경우) true일 수 있으므로, citations가
 *               비었다고 해서 자동으로 false로 취급하면 안 된다(PR #50 리뷰).
 *
 *               msgId는 이 턴의 AGENT 메시지 id다(이슈 #190). 한 턴이 PENDING msg 행 하나와
 *               1:1이라 이 값이 곧 턴 식별자 역할을 한다(D5) — 같은 턴의 delta들은 모두 같은
 *               msgId·seq를 달고 온다. seq는 턴이 시작되는 순간 확정되므로, 스트리밍 도중
 *               도착한 사람 메시지가 이 답변 앞으로 끼어들지 않는다.
 *
 *               turnId는 이 턴을 부른 발화에 클라이언트가 실은 값이다(이슈 #160). 협업방에서는
 *               남이 부른 턴도 같은 방에 흐르므로, 요청한 화면은 이 값으로 "내 질문의 답"을 고르고
 *               취소할 때도 이 값을 쓴다. 발화에 없었으면 null이다.
 *
 *               model은 이 턴에 실제로 쓴 게이트웨이 모델 별칭이다 — 발화가 지정했으면 그 값,
 *               비웠으면 서버 기본값이다.
 *
 *               groundingNotice는 방 문서 검색(#344)이 FOUND가 아니었을 때만 채워진다(이슈
 *               #353) — 재접속 복원 대상이 아니라 그 턴에만 실어 보내는 일시적 안내다.
 *
 *               agent는 협업방에서 `@SOL`·`@FIN`·`@LAW`로 멘션해 역할별 페르소나로 답했을 때만
 *               채워진다(이슈 #357) — `@AI`로 답했거나 DIRECT(1:1)면 null이다. citations와 달리
 *               이 값은 메시지에 저장돼 재접속 후에도 복원된다.
 *
 *               offset·content·mine은 이슈 #413(AI 턴이 연결과 떨어져 끝까지 감)에서 더했다. 모두 없을 수 있다(null).
 *               - offset: streaming 조각의 delta가 답변의 몇 번째 글자(UTF-16 단위 — Java String.length와 JS
 *                 string.length가 같다)부터인지. 화면은 이미 가진 범위를 버린다 — 중간에 들어온 화면이 받은 지금까지
 *                 내용과 이어 오는 조각이 겹쳐도 글자가 중복되지 않는다.
 *               - content: 답변의 처음부터 그 시점까지 전체. 끝 프레임(done·cancelled)의 최종 내용, 방에 들어올 때의
 *                 지금까지 내용, 1:1 같은 요청 재전송 내용에 싣는다. delta에 싣지 않는 것은 배포 순간 열려 있던 탭(옛
 *                 화면)이 delta를 이어 붙여 글자가 두 번 보이기 때문이다 — 옛 화면은 모르는 필드를 버린다.
 *               - mine: 받는 사람이 이 턴을 요청한 사람인지. 방 전체가 한 sink를 나눠 받아 방송 때는 null이고, 연결마다
 *                 내보내는 자리(ThreadWebSocketHandler)가 requesterSubject와 받는 사람을 비교해 채운다. 화면은 이것으로
 *                 돌아온 요청자에게 중지 버튼을 띄운다.
 *               requesterSubject는 서버 안에서만 드는 요청자 subject다 — 직렬화하지 않는다.
 */
public record ChatAnswerFrame(
		UUID threadId,
		UUID msgId,
		UUID turnId,
		String model,
		long seq,
		String delta,
		List<Citation> citations,
		boolean restrictedResultsOmitted,
		ChatAnswerStatus status,
		GroundingNotice groundingNotice,
		Agent agent,
		Integer offset,
		String content,
		Boolean mine,
		@JsonIgnore String requesterSubject) implements WsFrame {

	/** 위치·전체 내용·요청자를 싣지 않는 프레임(거부·대기 취소·재전송 메아리 등). */
	public ChatAnswerFrame(UUID threadId, UUID msgId, UUID turnId, String model, long seq, String delta,
			List<Citation> citations, boolean restrictedResultsOmitted, ChatAnswerStatus status,
			GroundingNotice groundingNotice, Agent agent) {
		this(threadId, msgId, turnId, model, seq, delta, citations, restrictedResultsOmitted, status, groundingNotice,
				agent, null, null, null, null);
	}

	/** 받는 사람이 요청자인지 채운 복사본. 요청자를 모르는 프레임은 그대로 둔다. */
	ChatAnswerFrame forRecipient(String subject) {
		if (requesterSubject == null) {
			return this;
		}
		return new ChatAnswerFrame(threadId, msgId, turnId, model, seq, delta, citations, restrictedResultsOmitted,
				status, groundingNotice, agent, offset, content, requesterSubject.equals(subject), requesterSubject);
	}
}
