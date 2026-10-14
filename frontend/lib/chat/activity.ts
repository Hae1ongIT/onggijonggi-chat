/********************************************************
 파일명 : activity.ts (lib/chat)
 설 명 : AI 턴의 도구 실행 중 표시 문구(이슈 #397). 서버는 종류 코드(chat.activity의 kind)와 대상만 보내고 문구는
 여기서 고른다 — 오류 문구(lib/api/errors.ts)와 같은 원칙이다. 모르는 코드면 일반 문구를 쓴다. 새 도구가
 생기면 아래 표에 한 줄을 더한다.
 *********************************************************/

const LABEL_BY_KIND: Record<string, string> = {
  WEB_SEARCH: '웹 검색 중…',
  WEB_FETCH: '페이지 읽는 중…',
  DOCUMENT_SEARCH: '방 문서 찾는 중…',
  DOCUMENT_REVISE: '개정본 만드는 중…',
};

const GENERIC_LABEL = '작업 중…';

/** 진행 중 표시 문구. 대상(검색어·주소·파일 이름)이 있으면 뒤에 붙인다. */
export function activityLabel(kind: string, target: string | null): string {
  const label = LABEL_BY_KIND[kind] ?? GENERIC_LABEL;
  if (!target) return label;
  return `${label} ${kind === 'WEB_FETCH' ? readableUrl(target) : target}`;
}

/** 주소의 %인코딩(한글 경로 등)을 사람이 읽을 수 있게 푼다. 풀 수 없으면 그대로 둔다. */
function readableUrl(url: string): string {
  try {
    return decodeURI(url);
  } catch {
    return url;
  }
}
