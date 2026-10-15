/********************************************************
 파일명 : citations.test.ts (lib/chat)
 설 명 : 근거 종류 기본값, 같은 근거를 가리는 키, 링크로 열 웹 주소 판정(이슈 #401).
 *********************************************************/

import { describe, expect, it } from 'vitest';
import type { Citation } from '@/lib/api/chat';
import { citationKey, citationKind, hostOf, safeWebUrl } from './citations';

const base: Citation = { title: '제목', snippet: '발췌', score: 0.5 };

describe('citationKind', () => {
  it('kind가 없던 과거 근거는 방 문서다', () => {
    expect(citationKind({ ...base, docId: 'd1' })).toBe('DOCUMENT');
    expect(citationKind({ ...base, kind: 'WEB' })).toBe('WEB');
  });
});

describe('citationKey', () => {
  it('방 문서는 청크, 없으면 문서로 가린다', () => {
    expect(citationKey({ ...base, docId: 'd1', chunkId: 'd1:2:0' })).toBe(
      'd1:2:0',
    );
    expect(citationKey({ ...base, docId: 'd1' })).toBe('d1');
  });

  it('웹 출처는 주소로 가리고, 없으면 null이다', () => {
    expect(
      citationKey({ ...base, kind: 'WEB', docId: null, url: 'https://a.com/' }),
    ).toBe('https://a.com/');
    expect(citationKey({ ...base, kind: 'WEB', docId: null })).toBeNull();
  });
});

describe('safeWebUrl', () => {
  it('http·https만 링크로 연다', () => {
    expect(safeWebUrl('https://spring.io/guides')).toBe(
      'https://spring.io/guides',
    );
    expect(safeWebUrl('http://a.com')).toBe('http://a.com/');
    expect(safeWebUrl('javascript:alert(1)')).toBeNull();
    expect(safeWebUrl('data:text/html,hi')).toBeNull();
    expect(safeWebUrl('주소 아님')).toBeNull();
    expect(safeWebUrl(null)).toBeNull();
  });
});

describe('hostOf', () => {
  it('www.를 뗀 호스트를 돌려준다', () => {
    expect(hostOf('https://www.spring.io/guides')).toBe('spring.io');
    expect(hostOf('잘못된 주소')).toBeNull();
  });
});
