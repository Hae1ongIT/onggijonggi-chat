// @vitest-environment jsdom

/********************************************************
 파일명 : collab-room-turn.test.tsx (components/collab)
 설 명 : 협업방 말풍선이 AI 답변의 끝 상태(중지·거부·실패)를 1:1과 같은 문구로 보이는지(이슈 #413 결정 11), 방에 막
 들어와 받은 "생각 중"(THINKING)은 협업방에서 자리 표시를 만들지 않는지(이슈 #413 결정 9)를 본다. 방 훅과 REST는 목으로 대체한다.
 *********************************************************/

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchThreadParticipants } from '@/lib/api/collab';
import { listThreadApprovals } from '@/lib/api/thread-approvals';
import {
  type RoomActivity,
  type RoomMessage,
  type TerminalStatus,
  initialRoomState,
} from '@/lib/chat/room-state';
import { useCollabRoom } from '@/lib/collab/use-collab-room';
import { CollabRoom } from './collab-room';

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn() }),
}));
vi.mock('@/lib/collab/use-collab-room', () => ({ useCollabRoom: vi.fn() }));
vi.mock('@/lib/api/collab', () => ({
  fetchCollabThreads: vi.fn().mockResolvedValue([]),
  fetchThreadParticipants: vi.fn(),
}));
vi.mock('@/lib/api/thread-approvals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/thread-approvals')>()),
  listThreadApprovals: vi.fn(),
}));
vi.mock('@/components/sidebar-toggle', () => ({ SidebarToggle: () => null }));
vi.mock('@/components/thread-documents', () => ({
  ThreadDocumentsProvider: ({ children }: { children: React.ReactNode }) =>
    children,
  ThreadDocumentsButton: () => null,
  ThreadDocumentsPanel: () => null,
}));
vi.mock('./participants-sheet', () => ({ ParticipantsSheet: () => null }));
vi.mock('./collab-input', () => ({ CollabInput: () => null }));
vi.mock('@/components/use-scroll-to-bottom', () => ({
  useScrollToBottom: () => [{ current: null }, { current: null }],
}));

const useRoom = vi.mocked(useCollabRoom);

function aiAnswer(
  id: string,
  content: string,
  terminalStatus: TerminalStatus | null,
): RoomMessage {
  return {
    id,
    seq: 1,
    from: null,
    content,
    streaming: false,
    terminalStatus,
    turnId: null,
    mine: false,
    citations: [],
    restrictedResultsOmitted: false,
    groundingNotice: null,
    agent: null,
    attachments: [],
  };
}

function room(messages: RoomMessage[], activity: RoomActivity | null = null) {
  return {
    state: { ...initialRoomState, messages, activity },
    connection: 'open' as const,
    send: vi.fn(),
    cancel: vi.fn(),
    cancellableTurnId: null,
    dismissError: vi.fn(),
    dismissNotice: vi.fn(),
    participantsRevision: 0,
    approvalsRevision: 0,
    typing: [],
    setTyping: vi.fn(),
  };
}

beforeEach(() => {
  vi.mocked(listThreadApprovals).mockResolvedValue([]);
  vi.mocked(fetchThreadParticipants).mockResolvedValue([]);
});

afterEach(cleanup);

describe('CollabRoom — AI 답변의 끝 상태(이슈 #413)', () => {
  it('중지·거부·실패를 1:1과 같은 문구로 보인다 — 본문이 있으면 그 아래, 없으면 이 줄만', () => {
    useRoom.mockReturnValue(
      room([
        aiAnswer('a1', '받은 데까지', 'cancelled'),
        aiAnswer('a2', '', 'denied'),
        aiAnswer('a3', '', 'failed'),
        aiAnswer('a4', '평범한 답', null),
      ]),
    );

    render(<CollabRoom threadId="room-1" />);

    expect(screen.getByText('받은 데까지')).toBeTruthy();
    expect(screen.getByText('응답이 중단되었습니다.')).toBeTruthy();
    expect(
      screen.getByText(
        '요청이 많아 이번 응답은 처리되지 않았습니다. 다시 시도해 주세요.',
      ),
    ).toBeTruthy();
    expect(screen.getByText('답변을 만들지 못했습니다.')).toBeTruthy();
  });

  it('말풍선이 생기기 전의 "생각 중"은 자리 표시를 만들지 않고, 도구 진행은 지금처럼 만든다', () => {
    const thinking: RoomActivity = {
      msgId: 'not-yet',
      kind: 'THINKING',
      target: null,
      agent: null,
    };
    useRoom.mockReturnValue(room([], thinking));
    const { unmount } = render(<CollabRoom threadId="room-1" />);
    expect(screen.queryByText('생각 중…')).toBeNull();
    unmount();

    useRoom.mockReturnValue(
      room([], { ...thinking, kind: 'WEB_SEARCH', target: '날씨' }),
    );
    render(<CollabRoom threadId="room-1" />);
    expect(screen.getByText(/웹 검색 중…/)).toBeTruthy();
  });
});
