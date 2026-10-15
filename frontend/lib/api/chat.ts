/********************************************************
 파일명 : chat.ts (lib/api)
 설 명 : 채팅 BFF 호출. 엔드포인트 URL·요청 바디 변환을 한 곳에 캡슐화한다. 인용은 이슈 #163부터
 REST가 아니라 chat.answer WS 프레임으로 오므로, 여기 CitationsResponse 타입만 그 모양을
 공유하는 용도로 남는다.
 *********************************************************/

import { CHAT_SESSIONS_PATH, bffUrl, chatSessionPath } from './config';
import { authFetch } from './http';

export interface ChatSessionSummary {
  id: string;
  title: string;
  createdAt: string;
}

/** 1:1 목록은 SSR이 아닌 클라이언트에서 갱신한다(#162). */
export async function fetchChatSessions(): Promise<ChatSessionSummary[]> {
  const response = await authFetch(bffUrl(CHAT_SESSIONS_PATH));
  if (!response.ok) throw new Error(await response.text());
  return response.json() as Promise<ChatSessionSummary[]>;
}

/** 와이어 메시지 모양. */
/** 인용 응답. 방 문서 근거와 웹 출처(이슈 #401)를 kind로 나눠 한 배열에 담는다. */
export interface Citation {
  /** 근거 종류 — 'DOCUMENT'(방 문서) 또는 'WEB'(웹 출처). 이 필드가 없던 과거 메시지는 DOCUMENT로
   * 본다(lib/chat/citations.ts citationKind). 나중에 종류가 늘 수 있어 string으로 둔다. */
  kind?: string;
  /** 방 문서 id. 웹 출처는 null이다. */
  docId?: string | null;
  title: string;
  snippet: string;
  /** 방 문서 검색 점수. 웹 출처는 0이고 화면에 보이지 않는다. */
  score: number;
  /** 이 근거가 나온 청크의 식별자(이슈 #352). 같은 문서에서 청크 여러 개가 근거로 쓰이면
   * docId가 같아지므로, 화면이 항목을 구분하는 키로 쓴다. 위치 필드가 없던 과거 메시지의
   * citations에는 없거나 null일 수 있다. 웹 출처는 null이다. */
  chunkId?: string | null;
  /** 문서 내 위치(PDF는 "page=N", 그 외는 "para=N", 이슈 #352). 위치 필드가 없던 과거
   * 메시지의 citations에는 없거나 null일 수 있다. 웹 출처는 null이다. */
  loc?: string | null;
  /** 웹 출처 주소(이슈 #401). 방 문서 근거는 null이다. */
  url?: string | null;
  /** 웹 출처를 처음 찾은 검색어. 검색 없이 읽은 주소와 방 문서 근거는 null이다. */
  query?: string | null;
  /** 웹 출처의 본문을 웹 읽기로 실제로 읽었는지. */
  read?: boolean;
}

export interface CitationsResponse {
  citations: Citation[];
  restrictedResultsOmitted: boolean;
}

/** 서버에 저장된 세션을 삭제한다. 스토어의 deleteSession(zustand 로컬 액션)과 이름이 겹치지
 * 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function deleteSessionOnServer(sessionId: string): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'DELETE',
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}

/** 서버에 저장된 세션 제목을 바꾼다. 스토어의 renameSession(zustand 로컬 액션)과 이름이
 * 겹치지 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function renameSessionOnServer(
  sessionId: string,
  title: string,
): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}
