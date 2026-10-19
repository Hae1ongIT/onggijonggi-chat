import { describe, expect, it } from 'vitest';
import type { Citation } from '@/lib/api/chat';
import type { ThreadMessageItem } from '@/lib/api/thread-history';
import type {
  ChatAnswerFrame,
  MessageAttachment,
  PresenceParticipant,
  WsFrame,
} from '@/lib/transport/frames';
import {
  type RoomMessage,
  type RoomState,
  applyFrame,
  applyHistory,
  catchUpCursor,
  clearActivity,
  clearRoomError,
  dismissNotice,
  historyCursor,
  initialRoomState,
  isForbidden,
  isApprovalNotice,
  isMembershipNotice,
  isNotice,
} from './room-state';

const THREAD = 'thread-1';

/** subject를 받아 그 사람을 만든다. 표시 이름은 subject에서 파생시켜 둘이 섞이면 눈에 띄게 한다. */
function person(subject: string): PresenceParticipant {
  return { subject, displayName: `${subject} 님` };
}

function join(subject: string): WsFrame {
  return { type: 'presence.join', threadId: THREAD, ...person(subject) };
}

function leave(subject: string): WsFrame {
  return { type: 'presence.leave', threadId: THREAD, ...person(subject) };
}

function snapshot(...subjects: string[]): WsFrame {
  return {
    type: 'presence.snapshot',
    threadId: THREAD,
    participants: subjects.map(person),
  };
}

/** 프레임마다 새 msgId를 준다 — 서버가 메시지마다 다른 id를 싣는 것과 같다(이슈 #190). */
let frameCounter = 0;

function say(
  from: string,
  content: string,
  msgId?: string,
  seq?: number,
  attachments: MessageAttachment[] = [],
): WsFrame {
  frameCounter += 1;
  return {
    type: 'chat.message',
    threadId: THREAD,
    msgId: msgId ?? `msg-${frameCounter}`,
    clientMsgId: null,
    turnId: null,
    seq: seq ?? frameCounter,
    from,
    fromDisplayName: `${from} 님`,
    content,
    attachments,
  };
}

function notice(
  severity: 'warning' | 'info',
  code: string,
  message: string,
): WsFrame {
  return {
    type: 'system.notice',
    threadId: THREAD,
    severity,
    code,
    message,
    traceId: `trace-${code}`,
  };
}

function answer(
  delta: string,
  status: 'streaming' | 'done' | 'cancelled' | 'denied' | 'failed',
  metadata: {
    citations?: Citation[];
    restrictedResultsOmitted?: boolean;
    /** 같은 턴의 패킷은 같은 msgId를 단다. 턴을 나누고 싶을 때만 다른 값을 준다. */
    msgId?: string;
    groundingNotice?: ChatAnswerFrame['groundingNotice'];
    agent?: ChatAnswerFrame['agent'];
  } = {},
): WsFrame {
  return {
    type: 'chat.answer',
    threadId: THREAD,
    msgId: metadata.msgId ?? 'agent-msg-1',
    turnId: null,
    model: 'm',
    seq: 1000,
    delta,
    citations: metadata.citations ?? [],
    restrictedResultsOmitted: metadata.restrictedResultsOmitted ?? false,
    status,
    groundingNotice: metadata.groundingNotice ?? null,
    agent: metadata.agent ?? null,
  };
}

/** 참가자가 되거나 빠진 변경. 서버는 이 변경마다 입퇴장 줄을 남기고 그 msgId·seq를 싣는다. */
function membershipChanged(
  action: 'INVITED' | 'REMOVED',
  subject: string,
  msgId: string,
  seq: number,
): WsFrame {
  return {
    type: 'participant.changed',
    threadId: THREAD,
    action,
    ...person(subject),
    msgId,
    seq,
  };
}

/** 승인 요청이 생기거나 처리된 사건. 서버는 사건마다 승인 줄을 남기고 그 msgId·seq를 싣는다. */
function approvalChanged(
  event: string,
  msgId: string | null,
  seq: number | null,
  originMsgId: string | null = 'orig-1',
  reason: string | null = null,
): WsFrame {
  return {
    type: 'approval.changed',
    threadId: THREAD,
    approvalId: 'apv-1',
    event,
    requesterSubject: 'sujin',
    requesterDisplayName: 'sujin 님',
    targetSubject: 'minho',
    targetDisplayName: 'minho 님',
    msgId,
    seq,
    originMsgId,
    reason,
  };
}

/** 입퇴장 시스템 라인을 뺀 사람·AI 메시지만. 대화 쪽을 보는 테스트가 쓴다. */
function chats(state: RoomState): RoomMessage[] {
  return state.messages.filter(
    (entry): entry is RoomMessage => !isNotice(entry),
  );
}

/** 프레임을 순서대로 접는다 — 테스트가 화면 없이 대화 한 판을 재현하는 방법이다. */
function fold(
  frames: WsFrame[],
  from: RoomState = initialRoomState,
): RoomState {
  return frames.reduce(applyFrame, from);
}

describe('applyFrame - presence.join', () => {
  it('입장 순서대로 참여자를 쌓는다', () => {
    const state = fold([join('sujin'), join('minho')]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('같은 사람의 join이 두 번 와도 한 번만 센다', () => {
    const state = fold([join('sujin'), join('minho'), join('sujin')]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });
});

describe('applyFrame - presence.snapshot', () => {
  it('붙는 순간 받은 명단으로 목록을 채운다 (본인 포함)', () => {
    // 서버가 보내는 명단에는 본인이 들어 있다 — 자기 입장은 자기가 받지 않기 때문이다.
    const state = fold([snapshot('sujin', 'minho')]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('재연결로 다시 오면 그 사이 놓친 입퇴장까지 한 번에 맞춘다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      snapshot('minho', 'jiwoo'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('jiwoo')]);
  });

  it('명단 뒤에 도착한 입퇴장을 이어서 반영한다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      join('jiwoo'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('jiwoo')]);
  });
});

describe('applyFrame - presence.leave', () => {
  it('나간 사람을 목록에서 지운다', () => {
    const state = fold([join('sujin'), join('minho'), leave('sujin')]);
    expect(state.participants).toEqual([person('minho')]);
  });

  it('목록에 없는 사람의 퇴장은 아무것도 바꾸지 않는다', () => {
    // 스냅샷을 못 받아 존재를 모르던 사람의 퇴장이 도착할 수 있다(#26 코멘트).
    const state = fold([join('sujin'), leave('minho')]);
    expect(state.participants).toEqual([person('sujin')]);
  });

  it('나갔다 다시 들어오면 맨 뒤에 붙는다', () => {
    const state = fold([
      join('sujin'),
      join('minho'),
      leave('sujin'),
      join('sujin'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('sujin')]);
  });

  it('참여자만 건드리고 대화 메시지는 남긴다', () => {
    const state = fold([
      join('sujin'),
      say('sujin', '먼저 가볼게요'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual([]);
    expect(chats(state)).toHaveLength(1);
  });
});

describe('applyFrame - 입퇴장 줄', () => {
  it('접속·해제는 흐름에 줄을 남기지 않는다 — 방 목록과 방을 오가는 것은 입퇴장이 아니다', () => {
    const state = fold([
      join('sujin'),
      say('sujin', '안녕하세요'),
      leave('sujin'),
    ]);
    expect(state.messages).toHaveLength(1);
    expect(chats(state)).toHaveLength(1);
  });

  it('명단(스냅샷)은 줄을 만들지 않는다 — 상태이지 사건이 아니다', () => {
    const state = fold([snapshot('sujin', 'minho')]);
    expect(state.messages).toEqual([]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('참가자가 되거나 빠지면 서버가 남긴 줄을 seq 자리에 넣는다', () => {
    const state = fold([
      say('sujin', '먼저', 'a', 1),
      say('sujin', '나중', 'c', 3),
      membershipChanged('INVITED', 'minho', 'j', 2),
      membershipChanged('REMOVED', 'minho', 'l', 4),
    ]);
    expect(state.messages.map((entry) => entry.id)).toEqual([
      'a',
      'j',
      'c',
      'l',
    ]);
    expect(state.messages[1]).toEqual({
      id: 'j',
      seq: 2,
      event: 'join',
      participant: person('minho'),
    });
    expect(state.messages[3]).toEqual({
      id: 'l',
      seq: 4,
      event: 'leave',
      participant: person('minho'),
    });
  });

  it('줄을 남기지 않은 변경(초대 대기 등)은 흐름을 건드리지 않는다', () => {
    const before = fold([say('sujin', '안녕하세요')]);
    const after = applyFrame(before, {
      type: 'participant.changed',
      threadId: THREAD,
      action: 'INVITE_PENDING',
      ...person('minho'),
      msgId: null,
      seq: null,
    });
    expect(after).toBe(before);
  });

  it('같은 줄이 이력과 실시간으로 겹쳐 와도 한 번만 남는다', () => {
    const live = fold([membershipChanged('INVITED', 'minho', 'j', 2)]);
    const state = applyFrame(
      live,
      membershipChanged('INVITED', 'minho', 'j', 2),
    );
    expect(state.messages).toHaveLength(1);
  });

  it('실시간 줄은 커서를 밀지 않는다 — 그보다 작은 seq의 대화가 아직 오는 중일 수 있다', () => {
    const state = fold([
      say('sujin', '먼저', 'a', 1),
      membershipChanged('INVITED', 'minho', 'j', 5),
    ]);
    expect(state.lastSeq).toBe(1);
  });

  it('입퇴장 줄이 흐르는 AI 답변을 끊지 않는다', () => {
    const state = fold([
      answer('요약을 ', 'streaming'),
      membershipChanged('INVITED', 'minho', 'j', 1001),
      answer('시작합니다', 'done'),
    ]);
    expect(chats(state)).toHaveLength(1);
    expect(chats(state)[0].content).toBe('요약을 시작합니다');
    expect(isMembershipNotice(state.messages[1])).toBe(true);
  });
});

describe('applyFrame - approval.changed', () => {
  it('서버가 남긴 승인 줄을 seq 자리에 넣는다', () => {
    const state = fold([
      say('sujin', '먼저', 'a', 1),
      say('sujin', '나중', 'c', 3),
      approvalChanged('REQUESTED', 'r', 2),
    ]);
    expect(state.messages.map((entry) => entry.id)).toEqual(['a', 'r', 'c']);
    expect(state.messages[1]).toEqual({
      id: 'r',
      seq: 2,
      approvalId: 'apv-1',
      event: 'requested',
      requester: person('sujin'),
      target: person('minho'),
      originMsgId: 'orig-1',
      reason: null,
    });
    expect(isApprovalNotice(state.messages[1])).toBe(true);
    expect(isMembershipNotice(state.messages[1])).toBe(false);
  });

  it('원글 id와 반려 사유를 줄에 싣는다', () => {
    const state = fold([
      approvalChanged('REJECTED', 'q', 3, 'orig-2', '3번 항목 근거가 없다'),
    ]);
    expect(state.messages[0]).toMatchObject({
      event: 'rejected',
      originMsgId: 'orig-2',
      reason: '3번 항목 근거가 없다',
    });
  });

  it('승인·반려 줄은 각각 approved·rejected가 된다', () => {
    const state = fold([
      approvalChanged('APPROVED', 'p', 2),
      approvalChanged('REJECTED', 'q', 3),
    ]);
    expect(
      state.messages.map((entry) => 'event' in entry && entry.event),
    ).toEqual(['approved', 'rejected']);
  });

  it('줄을 남기지 못했거나 모르는 사건이면 흐름을 건드리지 않는다', () => {
    const before = fold([say('sujin', '안녕하세요')]);
    expect(applyFrame(before, approvalChanged('REQUESTED', null, null))).toBe(
      before,
    );
    expect(applyFrame(before, approvalChanged('EXPIRED', 'x', 9))).toBe(before);
  });

  it('같은 줄이 이력과 실시간으로 겹쳐 와도 한 번만 남고 커서는 밀지 않는다', () => {
    const state = fold([
      say('sujin', '먼저', 'a', 1),
      approvalChanged('REQUESTED', 'r', 5),
      approvalChanged('REQUESTED', 'r', 5),
    ]);
    expect(state.messages).toHaveLength(2);
    expect(state.lastSeq).toBe(1);
  });

  it('승인 줄이 흐르는 AI 답변을 끊지 않는다', () => {
    const state = fold([
      answer('요약을 ', 'streaming'),
      approvalChanged('REQUESTED', 'r', 1001),
      answer('시작합니다', 'done'),
    ]);
    expect(chats(state)).toHaveLength(1);
    expect(chats(state)[0].content).toBe('요약을 시작합니다');
  });
});

describe('applyFrame - chat.message', () => {
  it('보낸 사람을 함께 남긴다', () => {
    const state = fold([
      say('sujin', '이 계약서 확인 부탁해요', 'msg-sujin-1'),
    ]);
    expect(state.messages).toEqual([
      {
        id: 'msg-sujin-1',
        seq: expect.any(Number),
        from: person('sujin'),
        content: '이 계약서 확인 부탁해요',
        streaming: false,
        terminalStatus: null,
        turnId: null,
        mine: false,
        citations: [],
        restrictedResultsOmitted: false,
        groundingNotice: null,
        agent: null,
        attachments: [],
      },
    ]);
  });
});

describe('applyFrame - chat.answer', () => {
  it('여러 패킷을 말풍선 하나로 잇고 done에서 멈춘다', () => {
    const state = fold([
      answer('제12조에 ', 'streaming'),
      answer('따르면 ', 'streaming'),
      answer('상한은 10%입니다.', 'done'),
    ]);

    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({
      from: null,
      content: '제12조에 따르면 상한은 10%입니다.',
      streaming: false,
      terminalStatus: null,
    });
  });

  it('다른 턴의 답변은 새 말풍선이 된다', () => {
    // 예전에는 "앞 답변이 끝났는지"로 갈랐다. 이제 프레임이 턴 식별자(msgId)를 들고 오므로
    // 그 값으로 가른다(이슈 #190) — 턴이 겹쳐 도착해도 섞이지 않는다.
    const state = fold([
      answer('첫 답변', 'done'),
      answer('두 번째 답변', 'done', { msgId: 'agent-msg-2' }),
    ]);
    expect(chats(state).map((m) => m.content)).toEqual([
      '첫 답변',
      '두 번째 답변',
    ]);
  });

  it('내용이 없는 패킷으로 빈 말풍선을 만들지 않는다', () => {
    // delta 없이 status만 알리는 패킷도 계약상 유효하다(frames.ts).
    expect(fold([answer('', 'streaming')]).messages).toEqual([]);
  });

  it('답변이 흐르는 중에 다른 사람이 말해도 한 말풍선으로 잇는다', () => {
    // 협업방에서는 흔한 순서다. 맨 끝만 보고 이어붙이면 답변이 둘로 갈린다(PR #80 리뷰).
    const state = fold([
      answer('제12조에 ', 'streaming'),
      say('minho', '저도 그거 궁금했어요'),
      answer('따르면 10%입니다.', 'done'),
    ]);

    expect(chats(state).map((m) => m.from)).toEqual([null, person('minho')]);
    expect(state.messages[0]).toMatchObject({
      content: '제12조에 따르면 10%입니다.',
      streaming: false,
      terminalStatus: null,
    });
  });

  it('사람 메시지 뒤에 오면 그 메시지에 섞이지 않는다', () => {
    const state = fold([say('sujin', '@AI 요약해줘'), answer('요약', 'done')]);
    expect(chats(state).map((m) => m.from)).toEqual([person('sujin'), null]);
  });

  it('토큰보다 먼저 온 citations를 답변에 누적하고 docId 중복은 최신 값으로 바꾼다', () => {
    const first = {
      docId: 'd1',
      title: '초안',
      snippet: '첫 발췌',
      score: 0.5,
    };
    const updated = {
      docId: 'd1',
      title: '최종',
      snippet: '갱신 발췌',
      score: 0.9,
    };
    const second = {
      docId: 'd2',
      title: '추가',
      snippet: '둘째 발췌',
      score: 0.7,
    };

    const state = fold([
      answer('', 'streaming', { citations: [first] }),
      answer('답변', 'streaming', {
        citations: [updated, second],
        restrictedResultsOmitted: true,
      }),
      answer('', 'done'),
    ]);

    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({
      content: '답변',
      streaming: false,
      terminalStatus: null,
      citations: [updated, second],
      restrictedResultsOmitted: true,
    });
  });
});

describe('applyFrame - chat.answer 근거 합치기(이슈 #401)', () => {
  it('같은 문서의 청크 여러 개를 모두 남긴다', () => {
    const chunk = (chunkId: string): Citation => ({
      docId: 'd1',
      title: '규정.pdf',
      snippet: chunkId,
      score: 0.8,
      chunkId,
    });

    const state = fold([
      answer('답', 'streaming'),
      answer('', 'done', { citations: [chunk('d1:1:0'), chunk('d1:2:0')] }),
    ]);

    expect(chats(state)[0].citations.map((c) => c.chunkId)).toEqual([
      'd1:1:0',
      'd1:2:0',
    ]);
  });

  it('docId가 없는 웹 출처는 주소로 가린다', () => {
    const web = (url: string): Citation => ({
      kind: 'WEB',
      docId: null,
      title: url,
      snippet: '',
      score: 0,
      url,
    });

    const state = fold([
      answer('답', 'streaming'),
      answer('', 'done', {
        citations: [web('https://a.com/'), web('https://b.com/')],
      }),
    ]);

    expect(chats(state)[0].citations.map((c) => c.url)).toEqual([
      'https://a.com/',
      'https://b.com/',
    ]);
  });
});

describe('applyFrame - chat.answer의 중지·거부(이슈 #162, §3.2)', () => {
  it('취소는 흐름을 끝내고 사유를 남긴다', () => {
    const state = fold([
      answer('쓰다 만 답', 'streaming'),
      answer('', 'cancelled'),
    ]);
    expect(chats(state)[0]).toMatchObject({
      content: '쓰다 만 답',
      streaming: false,
      terminalStatus: 'cancelled',
    });
  });

  it('거부도 같은 자리에 남는다', () => {
    const state = fold([answer('', 'streaming', { citations: [] })]);
    // streaming 빈 패킷은 말풍선을 만들지 않으므로 아직 아무것도 없다.
    expect(chats(state)).toHaveLength(0);
    expect(chats(fold([answer('', 'denied')]))[0]).toMatchObject({
      streaming: false,
      terminalStatus: 'denied',
    });
  });

  it('본문이 비어도 말풍선을 만든다 — 없으면 중지된 사실이 사라진다', () => {
    // DENIED는 언제나 본문이 비고, 즉시 취소도 조각이 하나도 오지 않는다. 빈 패킷 필터가
    // 이것까지 걸러 버리면 화면에는 아무 일도 없었던 것처럼 보인다.
    const state = fold([answer('', 'cancelled')]);
    expect(chats(state)).toHaveLength(1);
    expect(chats(state)[0].content).toBe('');
  });

  it('평범하게 끝난 답변은 사유를 남기지 않는다', () => {
    const state = fold([answer('다 썼다', 'done')]);
    expect(chats(state)[0]).toMatchObject({
      streaming: false,
      terminalStatus: null,
    });
  });
});

describe('applyFrame - chat.answer의 groundingNotice(이슈 #353)', () => {
  it('근거를 못 찾았다는 안내가 delta 없이 와도 빈 말풍선을 버리지 않는다', () => {
    const state = fold([
      answer('', 'streaming', { groundingNotice: 'no_evidence' }),
    ]);
    expect(chats(state)[0]).toMatchObject({ groundingNotice: 'no_evidence' });
  });

  it('검색 실패 안내는 근거 없음 안내와 구분된다', () => {
    const state = fold([
      answer('일반 지식으로 답합니다', 'done', {
        groundingNotice: 'unavailable',
      }),
    ]);
    expect(chats(state)[0]).toMatchObject({ groundingNotice: 'unavailable' });
  });

  it('한 번 정해진 안내는 이후 패킷이 null을 보내도 지워지지 않는다', () => {
    const state = fold([
      answer('', 'streaming', { groundingNotice: 'no_evidence' }),
      answer('답변', 'done'),
    ]);
    expect(chats(state)[0]).toMatchObject({
      content: '답변',
      groundingNotice: 'no_evidence',
    });
  });

  it('근거를 찾은 답변은 안내가 없다', () => {
    const state = fold([answer('근거 있음', 'done')]);
    expect(chats(state)[0]).toMatchObject({ groundingNotice: null });
  });
});

describe('applyFrame - error', () => {
  it('FORBIDDEN은 방 접근 거부로 읽는다', () => {
    const state = fold([
      {
        type: 'error',
        threadId: THREAD,
        code: 'FORBIDDEN',
        message: '이 방에 접근할 수 없습니다.',
        traceId: 'trace-1',
      },
    ]);
    expect(isForbidden(state.error)).toBe(true);
    expect(state.error?.message).toBe('이 작업을 수행할 권한이 없어요.');
    expect(state.error?.traceId).toBe('trace-1');
  });

  it('그 밖의 오류는 접근 거부가 아니다', () => {
    const state = fold([
      {
        type: 'error',
        threadId: null,
        code: 'RATE_LIMITED',
        message: '요청이 많습니다.',
        traceId: 'trace-2',
      },
    ]);
    expect(isForbidden(state.error)).toBe(false);
    expect(state.error?.code).toBe('RATE_LIMITED');
  });

  it('AI 종결 오류만 오면(실패 프레임을 모르는 옛 서버) 흐름만 끝낸다 — msgId가 없어 실패로 단정하지 않는다', () => {
    const state = fold([
      answer('부분 답변', 'streaming'),
      {
        type: 'error',
        threadId: THREAD,
        code: 'MODEL_UNAVAILABLE',
        message: '모델 오류',
        traceId: 'trace-ai',
      },
    ]);

    expect(state.messages[0]).toMatchObject({
      content: '부분 답변',
      streaming: false,
      terminalStatus: null,
    });
  });

  it('턴 시간 제한 초과도 종결 오류다 — 흐름을 끝내고 시간 초과 문구를 띄운다(이슈 #389)', () => {
    const state = fold([
      answer('부분 답변', 'streaming'),
      {
        type: 'error',
        threadId: THREAD,
        code: 'AI_TURN_TIMEOUT',
        message: '응답 시간 제한을 넘었습니다.',
        traceId: 'trace-ai',
      },
    ]);

    expect(state.messages[0]).toMatchObject({
      content: '부분 답변',
      streaming: false,
      terminalStatus: null,
    });
    expect(state.error?.message).toBe(
      '답변이 시간 제한을 넘겨 중단됐어요. 질문을 나눠서 다시 요청해 주세요.',
    );
  });

  it('비종결 오류는 진행 중 AI 답변을 끝내지 않고 알림만 닫을 수 있다', () => {
    const state = fold([
      answer('진행 중', 'streaming'),
      {
        type: 'error',
        threadId: THREAD,
        code: 'MESSAGE_DELIVERY_FAILED',
        message: '전달 실패',
        traceId: 'trace-local',
      },
    ]);

    expect(chats(state)[0].streaming).toBe(true);
    expect(state.error?.message).toBe(
      '메시지를 전달하지 못했어요. 연결을 확인하고 다시 보내 주세요.',
    );
    expect(clearRoomError(state).error).toBeNull();
  });
});

describe('applyFrame', () => {
  it('받은 상태를 바꾸지 않는다', () => {
    const before = fold([join('sujin')]);
    const snapshot = structuredClone(before);
    applyFrame(before, say('sujin', '안녕하세요'));
    expect(before).toEqual(snapshot);
  });
});

describe('system.notice(#29)', () => {
  it('warning 알림은 방 위에 남는다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(state.notices).toEqual([
      {
        code: 'RISKY_CONTENT',
        message: '검토가 필요합니다.',
        traceId: 'trace-RISKY_CONTENT',
      },
    ]);
  });

  // 배치가 30초마다 도는 계약(#27)이라 같은 사유가 되풀이해 온다 — 쌓이면 방을 덮는다.
  it('같은 code가 또 오면 쌓이지 않고 그 자리에서 갈아끼운다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '첫 번째'),
      notice('warning', 'OTHER', '다른 사유'),
      notice('warning', 'RISKY_CONTENT', '두 번째'),
    ]);
    expect(state.notices.map((item) => item.message)).toEqual([
      '두 번째',
      '다른 사유',
    ]);
  });

  // 토스트로 지나가는 안내라 훅이 띄운다 — 배너 자리를 차지하면 안 된다.
  it('info 알림은 상태에 남지 않는다', () => {
    const state = fold([
      notice('info', 'TOKEN_BUDGET_LOW', '한도가 가깝습니다.'),
    ]);
    expect(state.notices).toEqual([]);
  });

  it('알림을 닫으면 그 code만 사라진다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
      notice('warning', 'OTHER', '다른 사유'),
    ]);
    expect(dismissNotice(state, 'RISKY_CONTENT').notices).toEqual([
      { code: 'OTHER', message: '다른 사유', traceId: 'trace-OTHER' },
    ]);
  });

  it('없는 code를 닫으면 상태를 그대로 둔다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(dismissNotice(state, 'NONE')).toBe(state);
  });

  it('문구가 비어 오면 빈 배너 대신 대체 문구를 남긴다', () => {
    const state = fold([notice('warning', 'RISKY_CONTENT', '')]);
    expect(state.notices[0].message).toBe('확인이 필요한 알림이 도착했습니다.');
  });

  it('알림은 메시지 흐름을 건드리지 않는다', () => {
    const state = fold([
      say('sujin', '안녕하세요'),
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(state.messages).toHaveLength(1);
  });
  it('participant.changed는 접속자 상태를 건드리지 않는다', () => {
    // 명단(REST)과 접속자(presence)는 다른 목록이다 — 이 프레임은 시트 재조회 신호일 뿐이다.
    const before = applyFrame(initialRoomState, {
      type: 'presence.snapshot',
      threadId: 'room-1',
      participants: [{ subject: 'sub-1', displayName: '나' }],
    });

    const after = applyFrame(before, {
      type: 'participant.changed',
      threadId: 'room-1',
      action: 'INVITE_PENDING',
      subject: 'sub-2',
      displayName: '초대된 사람',
      msgId: null,
      seq: null,
    });

    expect(after).toBe(before);
  });
});
describe('applyHistory - 방 진입 시 과거 대화(#190)', () => {
  const historyItem = (
    id: string,
    seq: number,
    content: string,
    overrides: Partial<ThreadMessageItem> = {},
  ): ThreadMessageItem => ({
    id,
    seq,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content,
    authorSubject: 'sujin',
    authorDisplayName: 'sujin 님',
    createdAt: '2026-09-10T01:00:00Z',
    completedAt: '2026-09-10T01:00:00Z',
    attachments: [],
    ...overrides,
  });

  it('seq 순서로 흐름 앞에 붙인다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('b', 5, '나중'),
      historyItem('a', 1, '먼저'),
    ]);
    expect(chats(state).map((m) => m.content)).toEqual(['먼저', '나중']);
  });

  it('중지·거부된 답변은 사유까지 복원한다 — 방을 다시 열어도 표시가 남아야 한다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 1, '쓰다 만 답', {
        athKind: 'AGENT',
        status: 'CANCELLED',
      }),
      historyItem('b', 2, '', { athKind: 'AGENT', status: 'DENIED' }),
    ]);
    expect(chats(state).map((m) => m.terminalStatus)).toEqual([
      'cancelled',
      'denied',
    ]);
  });

  it('저장된 근거(방 문서·웹 출처)를 복원하고, 없으면 빈 배열이다(이슈 #401)', () => {
    const document: Citation = {
      kind: 'DOCUMENT',
      docId: 'd1',
      title: '규정.pdf',
      snippet: '발췌',
      score: 0.9,
      chunkId: 'd1:1:0',
    };
    const web: Citation = {
      kind: 'WEB',
      docId: null,
      title: 'Guides',
      snippet: '요약',
      score: 0,
      url: 'https://spring.io/guides',
      query: '스프링',
      read: true,
    };
    const state = applyHistory(initialRoomState, [
      historyItem('a', 1, '답', {
        athKind: 'AGENT',
        citations: [document, web],
      }),
      historyItem('b', 2, '질문'),
    ]);
    expect(chats(state).map((m) => m.citations)).toEqual([[document, web], []]);
  });

  it('FAILED는 빈 본문이어도 실패로 남긴다 — 자리를 비운 사이 실패한 답을 돌아와 알 수 있게(이슈 #413 결정 8)', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 1, '', { athKind: 'AGENT', status: 'FAILED' }),
    ]);
    expect(chats(state)).toHaveLength(1);
    expect(chats(state)[0].terminalStatus).toBe('failed');
  });

  it('seq가 띄엄띄엄해도 그대로 받는다 — 블록 예약이 남긴 구멍이다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '하나'),
      historyItem('b', 97, '둘'),
    ]);
    expect(chats(state).map((m) => m.seq)).toEqual([0, 97]);
  });

  it('이미 WS로 받은 메시지는 msgId로 걸러 두 번 그리지 않는다', () => {
    const live = fold([say('sujin', '실시간으로 먼저 왔다', 'dup-1')]);
    const state = applyHistory(live, [
      historyItem('dup-1', 3, '실시간으로 먼저 왔다'),
    ]);
    expect(chats(state)).toHaveLength(1);
  });

  it('이력이 실시간보다 앞에 온다', () => {
    const live = fold([say('sujin', '방금 말', 'live-1')]);
    const state = applyHistory(live, [historyItem('old-1', 0, '예전 말')]);
    expect(chats(state).map((m) => m.content)).toEqual(['예전 말', '방금 말']);
  });

  it('AGENT는 보낸 사람 없이 AI 답변으로 남는다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '답변입니다', {
        athKind: 'AGENT',
        authorSubject: null,
        authorDisplayName: null,
      }),
    ]);
    expect(chats(state)[0].from).toBeNull();
  });

  it('아직 안 채워진 빈 예약 행(PENDING)은 빈 말풍선을 만들지 않는다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '', { athKind: 'AGENT', status: 'PENDING' }),
    ]);
    expect(state.messages).toEqual([]);
  });

  it('SYSTEM은 그리지 않는다 — 사람도 AI도 아닌 줄을 이 화면이 표현하지 못한다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '위험 표현이 감지되었습니다', { athKind: 'SYSTEM' }),
    ]);
    expect(state.messages).toEqual([]);
  });

  it('SYSTEM이어도 입퇴장 줄은 seq 자리에 그린다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('b', 2, '나중 말'),
      historyItem('j', 1, '', {
        athKind: 'SYSTEM',
        authorSubject: null,
        authorDisplayName: null,
        membership: {
          event: 'JOINED',
          subject: 'minho',
          displayName: 'minho 님',
        },
      }),
      historyItem('a', 0, '예전 말'),
    ]);
    expect(state.messages.map((entry) => entry.id)).toEqual(['a', 'j', 'b']);
    expect(state.messages[1]).toEqual({
      id: 'j',
      seq: 1,
      event: 'join',
      participant: person('minho'),
    });
  });

  it('SYSTEM이어도 승인 줄은 seq 자리에 그린다', () => {
    const approval = (
      id: string,
      seq: number,
      event: string,
    ): ThreadMessageItem =>
      historyItem(id, seq, '', {
        athKind: 'SYSTEM',
        authorSubject: null,
        authorDisplayName: null,
        approval: {
          event,
          approvalId: 'apv-1',
          requesterSubject: 'sujin',
          requesterDisplayName: 'sujin 님',
          targetSubject: 'minho',
          targetDisplayName: 'minho 님',
          originMsgId: 'orig-1',
          reason: null,
        },
      });
    const state = applyHistory(initialRoomState, [
      historyItem('b', 3, '나중 말'),
      approval('r', 1, 'REQUESTED'),
      approval('x', 2, 'EXPIRED'),
      historyItem('a', 0, '예전 말'),
    ]);
    // 모르는 사건(EXPIRED)은 그리지 않는다.
    expect(state.messages.map((entry) => entry.id)).toEqual(['a', 'r', 'b']);
    expect(state.messages[1]).toEqual({
      id: 'r',
      seq: 1,
      approvalId: 'apv-1',
      event: 'requested',
      requester: person('sujin'),
      target: person('minho'),
      originMsgId: 'orig-1',
      reason: null,
    });
    // 건너뛴 줄도 커서는 지나친다.
    expect(state.lastSeq).toBe(3);
  });

  it('원글 id·반려 사유가 생기기 전에 저장된 승인 줄은 둘 다 null로 읽는다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('r', 1, '', {
        athKind: 'SYSTEM',
        authorSubject: null,
        authorDisplayName: null,
        approval: {
          event: 'REQUESTED',
          approvalId: 'apv-1',
          requesterSubject: 'sujin',
          requesterDisplayName: 'sujin 님',
          targetSubject: 'minho',
          targetDisplayName: 'minho 님',
        },
      }),
    ]);
    expect(state.messages[0]).toMatchObject({
      event: 'requested',
      originMsgId: null,
      reason: null,
    });
  });

  it('첨부만 보낸 메시지는 본문이 비어도 첨부와 함께 남는다', () => {
    const attachment = { id: 'f1', fileName: '규정.pdf' };
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '', { attachments: [attachment] }),
    ]);
    expect(chats(state).map((m) => m.attachments)).toEqual([[attachment]]);
  });
});

describe('applyFrame - 첨부', () => {
  it('chat.message의 첨부를 메시지에 남긴다', () => {
    const attachment = { id: 'f1', fileName: '규정.pdf' };
    const state = fold([say('sujin', '요약해줘', 'm1', 1, [attachment])]);
    expect(chats(state)[0].attachments).toEqual([attachment]);
  });
});

describe('재접속 따라잡기 커서(#190)', () => {
  const item = (
    id: string,
    seq: number,
    content: string,
    overrides: Partial<ThreadMessageItem> = {},
  ): ThreadMessageItem => ({
    id,
    seq,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content,
    authorSubject: 'sujin',
    authorDisplayName: 'sujin 님',
    createdAt: '2026-09-10T01:00:00Z',
    completedAt: '2026-09-10T01:00:00Z',
    attachments: [],
    ...overrides,
  });

  it('아직 아무것도 못 받았으면 커서가 없다', () => {
    expect(initialRoomState.lastSeq).toBeNull();
  });

  it('실시간으로 받은 메시지가 커서를 민다', () => {
    const state = fold([say('sujin', '안녕', 'live-1', 42)]);
    expect(state.lastSeq).toBe(42);
  });

  it('이력을 받아도 커서가 가장 큰 seq로 간다', () => {
    const state = applyHistory(initialRoomState, [
      item('a', 3, '먼저'),
      item('b', 11, '나중'),
    ]);
    expect(state.lastSeq).toBe(11);
  });

  it('그리지 않은 줄(SYSTEM)도 커서는 지나친다 — 다음 따라잡기가 또 받으면 안 된다', () => {
    const state = applyHistory(initialRoomState, [
      item('sys', 7, '위험 감지', { athKind: 'SYSTEM' }),
    ]);
    expect(state.messages).toEqual([]);
    expect(state.lastSeq).toBe(7);
  });

  it('커서는 뒤로 가지 않는다', () => {
    const ahead = applyHistory(initialRoomState, [item('a', 20, '먼 미래')]);
    const behind = applyHistory(ahead, [item('b', 5, '과거')]);
    expect(behind.lastSeq).toBe(20);
  });

  it('따라잡은 메시지는 흐름 끝에 붙는다 — 이력보다 새것이다', () => {
    const entered = applyHistory(initialRoomState, [item('old', 0, '예전 말')]);
    const caughtUp = applyHistory(entered, [item('gap', 9, '끊긴 동안의 말')]);
    expect(chats(caughtUp).map((m) => m.content)).toEqual([
      '예전 말',
      '끊긴 동안의 말',
    ]);
  });

  it('따라잡기가 이미 받은 것과 겹쳐도 한 번만 남는다', () => {
    const live = fold([say('sujin', '이미 받은 말', 'dup-9', 9)]);
    const caughtUp = applyHistory(live, [
      item('dup-9', 9, '이미 받은 말'),
      item('new-10', 10, '못 받았던 말'),
    ]);
    expect(chats(caughtUp).map((m) => m.content)).toEqual([
      '이미 받은 말',
      '못 받았던 말',
    ]);
  });

  it('seq가 띄엄띄엄해도 따라잡기가 멈추지 않는다 — 구멍은 기다리지 않는다', () => {
    const entered = applyHistory(initialRoomState, [item('a', 0, '하나')]);
    const caughtUp = applyHistory(entered, [item('b', 97, '둘')]);
    expect(chats(caughtUp).map((m) => m.seq)).toEqual([0, 97]);
    expect(caughtUp.lastSeq).toBe(97);
  });
});

describe('applyFrame - chat.activity(이슈 #397)', () => {
  function activity(
    active: boolean,
    kind = 'WEB_SEARCH',
    target: string | null = '서울 날씨',
    msgId = 'agent-msg-1',
    agent: 'sol' | 'fin' | 'law' | null = null,
  ): WsFrame {
    return {
      type: 'chat.activity',
      threadId: THREAD,
      msgId,
      turnId: null,
      kind,
      target,
      active,
      agent,
    };
  }

  it('역할 에이전트를 함께 들어, 협업방 AI 자리에 그 이름을 쓸 수 있다', () => {
    const state = fold([
      activity(true, 'WEB_SEARCH', '환율', 'agent-msg-1', 'fin'),
    ]);
    expect(state.activity?.agent).toBe('fin');
  });

  it('같은 턴의 다음 도구 시작은 흐르는 답변이 있어도 표시를 바꾼다', () => {
    const state = fold([
      answer('먼저 찾아볼게요', 'streaming'),
      activity(true, 'WEB_FETCH', 'https://example.org'),
    ]);
    expect(state.activity?.kind).toBe('WEB_FETCH');
  });

  it('시작하면 들고, 끝나면 지운다', () => {
    const started = fold([activity(true)]);
    expect(started.activity).toEqual({
      msgId: 'agent-msg-1',
      kind: 'WEB_SEARCH',
      target: '서울 날씨',
      agent: null,
    });
    expect(fold([activity(false)], started).activity).toBeNull();
  });

  it('다음 도구가 시작하면 그 도구로 바뀐다 — 지금 도는 것만 둔다', () => {
    const state = fold([
      activity(true, 'DOCUMENT_SEARCH', '연장근로'),
      activity(false, 'DOCUMENT_SEARCH', '연장근로'),
      activity(true, 'DOCUMENT_REVISE', '규정.docx'),
    ]);
    expect(state.activity?.kind).toBe('DOCUMENT_REVISE');
    expect(state.activity?.target).toBe('규정.docx');
  });

  it('다른 턴의 끝 알림은 무시한다', () => {
    const state = fold([
      activity(true),
      activity(false, 'WEB_SEARCH', null, 'agent-msg-2'),
    ]);
    expect(state.activity?.msgId).toBe('agent-msg-1');
  });

  it('그 턴의 답변이 끝나면(완료·중지) 함께 지운다', () => {
    expect(
      fold([activity(true), answer('답', 'streaming')]).activity,
    ).not.toBeNull();
    expect(fold([activity(true), answer('', 'done')]).activity).toBeNull();
    expect(fold([activity(true), answer('', 'cancelled')]).activity).toBeNull();
  });

  it('종결 오류는 답변 말풍선이 없어도 지운다', () => {
    const state = fold([
      activity(true),
      {
        type: 'error',
        threadId: THREAD,
        code: 'MODEL_UNAVAILABLE',
        message: 'm',
        traceId: 't',
      },
    ]);
    expect(state.activity).toBeNull();
  });

  it('다른 턴의 답변 종료는 지금 표시를 지우지 않는다', () => {
    expect(
      fold([activity(true), answer('', 'done', { msgId: 'agent-msg-2' })])
        .activity?.msgId,
    ).toBe('agent-msg-1');
    expect(
      fold([activity(true), answer('', 'cancelled', { msgId: 'agent-msg-2' })])
        .activity?.msgId,
    ).toBe('agent-msg-1');
  });

  it('거부로 끝나도 지운다', () => {
    expect(fold([activity(true), answer('', 'denied')]).activity).toBeNull();
  });

  it('종결이 아닌 오류는 표시를 그대로 둔다', () => {
    const state = fold([
      activity(true),
      {
        type: 'error',
        threadId: THREAD,
        code: 'RATE_LIMITED',
        message: 'm',
        traceId: 't',
      },
    ]);
    expect(state.activity?.msgId).toBe('agent-msg-1');
  });

  it('이미 끝난 답변의 늦은 시작은 받지 않는다', () => {
    const state = fold([
      answer('답', 'streaming'),
      answer('', 'done'),
      activity(true),
    ]);
    expect(state.activity).toBeNull();
  });

  it('말풍선 없이 시작한 표시는 첫 조각에도 남고, 그 턴이 끝나면 지워진다', () => {
    const streaming = fold([activity(true), answer('첫 조각', 'streaming')]);
    expect(streaming.activity?.msgId).toBe('agent-msg-1');
    expect(fold([answer('', 'done')], streaming).activity).toBeNull();
  });

  it('입장·명단 프레임은 표시를 건드리지 않는다', () => {
    const state = fold([
      activity(true),
      {
        type: 'presence.snapshot',
        threadId: THREAD,
        participants: [],
      },
    ]);
    expect(state.activity?.msgId).toBe('agent-msg-1');
  });

  it('연결이 끊기면 clearActivity로 지운다', () => {
    expect(clearActivity(fold([activity(true)])).activity).toBeNull();
    expect(clearActivity(initialRoomState)).toBe(initialRoomState);
  });
});

/**
 * AI 턴이 연결과 떨어진 뒤(이슈 #413) 답변을 이어 받는 규칙 — 조각 위치(offset)·전체 내용(content)·받는 사람별 mine,
 * 실패 표시, 끊긴 사이 끝난 답의 마무리, 따라잡기 커서.
 */
describe('#413 답변 이어 받기', () => {
  const at = (
    delta: string,
    offset: number | null,
    extra: Partial<ChatAnswerFrame> = {},
  ): WsFrame => ({
    ...(answer(delta, 'streaming') as ChatAnswerFrame),
    offset,
    ...extra,
  });
  const content = (frame: RoomState) =>
    (chats(frame)[0] as RoomMessage).content;
  const historyRow = (
    id: string,
    seq: number,
    body: string,
    overrides: Partial<ThreadMessageItem> = {},
  ): ThreadMessageItem => ({
    id,
    seq,
    athKind: 'AGENT',
    status: 'COMPLETE',
    content: body,
    authorSubject: null,
    authorDisplayName: null,
    createdAt: '2026-10-19T01:00:00Z',
    completedAt: '2026-10-19T01:00:00Z',
    attachments: [],
    ...overrides,
  });

  it('들어올 때 받은 지금까지 내용(content)과 겹치는 조각은 위치로 잘라 붙인다 — 글자가 중복되지 않는다', () => {
    const state = fold([
      at('', null, { content: '안녕하세' }),
      at('하세', 2),
      at('세요', 3),
      at('!', 5),
    ]);
    expect(content(state)).toBe('안녕하세요!');
  });

  it('이미 가진 범위의 조각은 버리고, 빈틈이 생기면 붙이지 않고 끝 프레임의 전체 내용이 메운다', () => {
    const gap = fold([at('안녕', 0), at('요', 4)]);
    expect(content(gap)).toBe('안녕');
    const old = fold([at('안녕', 0), at('녕', 1)]);
    expect(content(old)).toBe('안녕');
    const done = applyFrame(gap, {
      ...(answer('', 'done') as ChatAnswerFrame),
      content: '안녕하세요',
    });
    expect(content(done)).toBe('안녕하세요');
    expect(chats(done)[0].streaming).toBe(false);
  });

  it('위치가 없는 조각(옛 서버)은 예전처럼 이어 붙이고, 지금 것보다 짧은 content는 늦게 온 것이라 무시한다', () => {
    const state = fold([
      at('안녕', null),
      at('하세요', null),
      at('', null, { content: '안' }),
    ]);
    expect(content(state)).toBe('안녕하세요');
  });

  it('끝난 말풍선은 늦게 온 streaming 프레임이 다시 열지 않는다 — 들어올 때 내용과 방 프레임은 순서가 섞여 온다', () => {
    const state = fold([
      at('안녕', 0),
      { ...(answer('', 'done') as ChatAnswerFrame), content: '안녕하세요' },
      at('', null, { content: '안녕하' }),
    ]);
    expect(chats(state)[0]).toMatchObject({
      content: '안녕하세요',
      streaming: false,
    });
  });

  it('협업방 중지도 cancelled다 — 받은 내용과 "중지됨"이 남는다', () => {
    const state = fold([
      at('부분', 0),
      { ...(answer('', 'cancelled') as ChatAnswerFrame), content: '부분' },
    ]);
    expect(chats(state)[0]).toMatchObject({
      content: '부분',
      terminalStatus: 'cancelled',
      streaming: false,
    });
  });

  it('실패는 그 msgId의 failed 끝 프레임으로 온다 — 받은 조각을 비우고 실패로 끝내며, 뒤따른 오류 프레임은 배너만 띄운다', () => {
    const state = fold([
      at('부분', 0, { msgId: 'mine-turn' }),
      {
        ...(answer('', 'failed', { msgId: 'mine-turn' }) as ChatAnswerFrame),
        content: '',
      },
      {
        type: 'error',
        threadId: THREAD,
        code: 'MODEL_UNAVAILABLE',
        message: '모델 오류',
        traceId: 'trace-ai',
      },
    ]);
    expect(chats(state)[0]).toMatchObject({
      content: '',
      streaming: false,
      terminalStatus: 'failed',
    });
    expect(state.error?.code).toBe('MODEL_UNAVAILABLE');
    // 첫 글자 전에 실패해도 말풍선이 생겨 실패가 보인다.
    const early = fold([
      { ...(answer('', 'failed') as ChatAnswerFrame), content: '' },
    ]);
    expect(chats(early)[0].terminalStatus).toBe('failed');
  });

  it('끝난 말풍선의 상태는 늦게 온 끝 프레임이 바꾸지 않는다 — 더 긴 최종 내용만 받는다', () => {
    const cancelled = fold([
      at('부분', 0),
      { ...(answer('', 'cancelled') as ChatAnswerFrame), content: '부분' },
      { ...(answer('', 'done') as ChatAnswerFrame), content: '부분과 더' },
    ]);
    expect(chats(cancelled)[0]).toMatchObject({
      content: '부분과 더',
      terminalStatus: 'cancelled',
    });
    const failed = fold([
      { ...(answer('', 'failed') as ChatAnswerFrame), content: '' },
      { ...(answer('', 'done') as ChatAnswerFrame), content: '되살아난 글' },
    ]);
    expect(chats(failed)[0]).toMatchObject({
      content: '',
      terminalStatus: 'failed',
    });
  });

  it('mine은 서버가 받는 사람마다 채운 값이고, 한 번 내 것이면 남는다', () => {
    const state = fold([
      at('', null, { content: '안녕', mine: true }),
      at('하세요', 2, { mine: null }),
    ]);
    expect(chats(state)[0].mine).toBe(true);
    expect(chats(fold([at('남의 답', 0, { mine: false })]))[0].mine).toBe(
      false,
    );
  });

  it('들어올 때 받은 답 말풍선은 이력으로 먼저 그린 뒤의 대기 질문보다 앞(seq 자리)에 놓인다', () => {
    const base = applyHistory(initialRoomState, [
      historyRow('q1', 1, '첫 질문', {
        athKind: 'HUMAN',
        authorSubject: 'sujin',
      }),
      historyRow('a1', 2, '', { status: 'PENDING', completedAt: null }),
      historyRow('q2', 3, '둘째 질문', {
        athKind: 'HUMAN',
        authorSubject: 'sujin',
      }),
    ]);
    const state = applyFrame(base, {
      ...(answer('', 'streaming', { msgId: 'a1' }) as ChatAnswerFrame),
      seq: 2,
      content: '지금까지',
    });
    expect(chats(state).map((message) => message.id)).toEqual([
      'q1',
      'a1',
      'q2',
    ]);
  });

  it('첫 글자가 오면 "생각 중"(THINKING) 표시를 지우고, 도구 표시는 남긴다', () => {
    const thinking: WsFrame = {
      type: 'chat.activity',
      threadId: THREAD,
      msgId: 'agent-msg-1',
      turnId: null,
      kind: 'THINKING',
      target: null,
      active: true,
      agent: null,
    };
    expect(fold([thinking]).activity?.kind).toBe('THINKING');
    expect(fold([thinking, at('안녕', 0)]).activity).toBeNull();
    // 들어올 때 받은 "생각 중"이 첫 조각보다 늦게 와도 남지 않는다.
    expect(fold([at('안녕', 0), thinking]).activity).toBeNull();
    const tool: WsFrame = { ...thinking, kind: 'WEB_SEARCH', target: '날씨' };
    expect(fold([tool, at('안녕', 0)]).activity?.kind).toBe('WEB_SEARCH');
  });

  it('화면에서 흐르는 중인 답이 이력에서 끝나 있으면 이력의 내용·상태·근거로 마무리하고 진행 표시를 지운다', () => {
    const citation: Citation = {
      kind: 'WEB',
      title: '출처',
      snippet: '',
      score: 0,
      url: 'https://a.example/',
    };
    const streaming = fold([
      at('부분', 0),
      {
        type: 'chat.activity',
        threadId: THREAD,
        msgId: 'agent-msg-1',
        turnId: null,
        kind: 'WEB_SEARCH',
        target: null,
        active: true,
        agent: null,
      },
    ]);
    const done = applyHistory(streaming, [
      historyRow('agent-msg-1', 1000, '완성된 답', { citations: [citation] }),
    ]);
    expect(chats(done)[0]).toMatchObject({
      content: '완성된 답',
      streaming: false,
      terminalStatus: null,
      citations: [citation],
    });
    expect(done.activity).toBeNull();
    const failed = applyHistory(streaming, [
      historyRow('agent-msg-1', 1000, '', { status: 'FAILED' }),
    ]);
    expect(chats(failed)[0]).toMatchObject({
      content: '',
      streaming: false,
      terminalStatus: 'failed',
    });
    const stillRunning = applyHistory(streaming, [
      historyRow('agent-msg-1', 1000, '', {
        status: 'PENDING',
        completedAt: null,
      }),
    ]);
    expect(chats(stillRunning)[0]).toMatchObject({
      content: '부분',
      streaming: true,
    });
  });

  it('따라잡기 커서는 흐르는 AI 답변과 이력에서 PENDING이던 답변 앞에서 멈춘다 — 끊긴 사이 끝난 답을 다시 가져온다', () => {
    const streaming = fold([say('sujin', '질문', 'q'), at('부분', 0)]);
    expect(streaming.lastSeq).toBe(1000);
    expect(catchUpCursor(streaming)).toBe(999);

    const loaded = applyHistory(initialRoomState, [
      historyRow('q1', 10, '질문', {
        athKind: 'HUMAN',
        authorSubject: 'sujin',
      }),
      historyRow('a1', 11, '', { status: 'PENDING', completedAt: null }),
      historyRow('q2', 12, '다음 질문', {
        athKind: 'HUMAN',
        authorSubject: 'sujin',
      }),
    ]);
    expect(loaded.lastSeq).toBe(12);
    expect(catchUpCursor(loaded)).toBe(10);
    expect(
      historyCursor(null, [
        historyRow('a1', 11, '', { status: 'PENDING', completedAt: null }),
        historyRow('q2', 12, '다음', { athKind: 'HUMAN' }),
      ]),
    ).toBe(10);

    // 실시간으로 말풍선이 생기면 이력의 PENDING으로는 더 멈추지 않는다(흐르는 말풍선으로 멈춘다).
    const live = applyFrame(loaded, {
      ...(answer('', 'streaming', { msgId: 'a1' }) as ChatAnswerFrame),
      seq: 11,
      content: '지금까지',
    });
    expect(live.pendingAnswers).toEqual({});
    expect(catchUpCursor(live)).toBe(10);

    // 다시 가져온 행이 끝나 있으면 그 답을 그리고 더는 멈추지 않는다.
    const caughtUp = applyHistory(loaded, [historyRow('a1', 11, '완성')]);
    expect(chats(caughtUp).map((message) => message.id)).toEqual([
      'q1',
      'a1',
      'q2',
    ]);
    expect(catchUpCursor(caughtUp)).toBe(12);
  });

  it('옛 화면 방식(delta만 이어 붙이기)으로 새 서버 프레임을 받아도 글자가 두 번 보이지 않는다 — 전체 내용이 content에만 실린다(결정 12)', () => {
    const frames: ChatAnswerFrame[] = [
      {
        ...(answer('', 'streaming') as ChatAnswerFrame),
        content: '지금까지',
        offset: null,
      },
      { ...(answer('더', 'streaming') as ChatAnswerFrame), offset: 4 },
      { ...(answer('', 'done') as ChatAnswerFrame), content: '지금까지더' },
    ];
    const oldScreen = frames.reduce((text, frame) => text + frame.delta, '');
    expect(oldScreen).toBe('더');
    expect(oldScreen).not.toContain('지금까지지금까지');
    expect(content(fold(frames))).toBe('지금까지더');
  });
});
