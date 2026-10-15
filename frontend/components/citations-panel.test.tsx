// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { Citation } from '@/lib/api/chat';
import { CitationsPanel } from './citations-panel';

afterEach(cleanup);

describe('CitationsPanel', () => {
  it('hides the loading indicator after an empty citation result', () => {
    const { rerender } = render(
      <CitationsPanel state={{ status: 'loading' }} />,
    );
    expect(screen.getByText('근거 검색 중...')).toBeTruthy();

    rerender(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    expect(screen.queryByText('근거 검색 중...')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows a human-readable location next to the title when loc is present', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [
            {
              docId: 'doc-1',
              title: '규정.pdf',
              snippet: '연차는 사흘 전에 신청한다.',
              score: 0.9,
              loc: 'page=3',
            },
          ],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText(/3쪽/)).toBeTruthy();
  });

  it('omits the location when loc is missing (past messages) or has an unknown shape', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [
            {
              docId: 'doc-1',
              title: '규정.pdf',
              snippet: '연차는 사흘 전에 신청한다.',
              score: 0.9,
            },
            {
              docId: 'doc-2',
              title: '취업규칙.docx',
              snippet: '제3조 근로시간',
              score: 0.8,
              loc: 'line=5',
            },
          ],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText(/쪽$/)).toBeNull();
    expect(screen.queryByText(/번째 문단$/)).toBeNull();
  });

  it('shows a notice when no evidence was found even with no citations', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: 'no_evidence',
        }}
      />,
    );

    expect(screen.getByText(/근거를 찾지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText(/일반 지식/)).toBeNull();
  });

  it('shows a different notice when the document search itself failed', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: 'unavailable',
        }}
      />,
    );

    expect(screen.getByText(/검색을 하지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText(/일반 지식/)).toBeNull();
  });

  describe('web sources (#401)', () => {
    const document: Citation = {
      kind: 'DOCUMENT',
      docId: 'doc-1',
      title: '규정.pdf',
      snippet: '연차는 사흘 전에 신청한다.',
      score: 0.9,
      chunkId: 'doc-1:1:0',
    };
    const searched: Citation = {
      kind: 'WEB',
      docId: null,
      title: 'Spring Guides',
      snippet: '가이드 모음',
      score: 0,
      url: 'https://www.spring.io/guides',
      query: '스프링 가이드',
      read: true,
    };
    const unsafe: Citation = {
      kind: 'WEB',
      docId: null,
      title: '수상한 링크',
      snippet: '',
      score: 0,
      url: 'javascript:alert(1)',
      query: '스프링 가이드',
      read: false,
    };

    it('shows only the web button when the answer used only the web', () => {
      render(
        <CitationsPanel state={{ status: 'success', citations: [searched] }} />,
      );

      expect(screen.getAllByRole('button')).toHaveLength(1);
      expect(screen.getByRole('button').textContent).toContain(
        '🌐 웹 출처 (1건)',
      );
      expect(screen.queryByText(/근거 검색됨/)).toBeNull();
    });

    it('keeps document citations and web sources under separate buttons', () => {
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [document, searched, unsafe],
            groundingNotice: null,
          }}
        />,
      );

      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.textContent)).toEqual([
        expect.stringContaining('📚 근거 검색됨 (1건)'),
        expect.stringContaining('🌐 웹 출처 (2건)'),
      ]);

      fireEvent.click(buttons[1]);

      expect(screen.getByText('검색어: 스프링 가이드')).toBeTruthy();
      const link = screen.getByRole('link', { name: 'Spring Guides' });
      expect(link.getAttribute('href')).toBe('https://www.spring.io/guides');
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(screen.getByText(/spring\.io$/)).toBeTruthy();
      expect(screen.getAllByText('읽음')).toHaveLength(1);
      expect(screen.getByText('수상한 링크').closest('a')).toBeNull();
      expect(screen.queryByText('규정.pdf')).toBeNull();
    });

    it('shows the web button next to the no-evidence notice', () => {
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [searched],
            groundingNotice: 'no_evidence',
          }}
        />,
      );

      expect(screen.getByText(/근거를 찾지 못했습니다/)).toBeTruthy();
      expect(screen.getByRole('button').textContent).toContain('🌐 웹 출처');
    });

    it('ignores citation kinds it does not know', () => {
      const { container } = render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [{ ...document, kind: 'GENERATED' }],
          }}
        />,
      );

      expect(container.textContent).toBe('');
    });
  });

  it('renders nothing when there is no citation, no restriction, and no notice', () => {
    const { container } = render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: null,
        }}
      />,
    );

    expect(container.textContent).toBe('');
  });
});
