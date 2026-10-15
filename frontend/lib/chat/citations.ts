/********************************************************
 파일명 : citations.ts (lib/chat)
 설 명 : 근거(citations) 한 건을 다루는 작은 규칙들(이슈 #401). 방 문서 근거와 웹 출처가 한 배열로 오므로
 종류를 가리고(kind가 없던 과거 근거는 방 문서), 같은 근거를 합치는 키를 고르고, 웹 주소를 링크로 열어도
 되는지 판단한다.
 *********************************************************/

import type { Citation } from '@/lib/api/chat';

export const DOCUMENT_CITATION = 'DOCUMENT';
export const WEB_CITATION = 'WEB';

/** 근거 종류. kind가 없던 과거 근거는 방 문서다. */
export function citationKind(citation: Citation): string {
  return citation.kind ?? DOCUMENT_CITATION;
}

/**
 * 같은 근거를 가리는 키. 방 문서는 청크(없으면 문서), 웹은 주소다 — 같은 문서의 청크 여러 개가 하나로 뭉치지
 * 않고, 웹 출처끼리도 docId가 없어 뭉치지 않게 한다. 둘 다 없으면 null이다.
 */
export function citationKey(citation: Citation): string | null {
  if (citationKind(citation) === WEB_CITATION) return citation.url ?? null;
  return citation.chunkId ?? citation.docId ?? null;
}

/** 링크로 열어도 되는 웹 주소 — http·https만. 그 밖(javascript: 등)이나 해석할 수 없으면 null이다. */
export function safeWebUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:'
      ? parsed.href
      : null;
  } catch {
    return null;
  }
}

/** 주소의 호스트(www. 제외). 해석할 수 없으면 null이다. */
export function hostOf(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return null;
  }
}
