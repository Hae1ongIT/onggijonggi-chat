// @vitest-environment jsdom
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  act,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThreadDocuments } from './thread-documents';
import * as api from '@/lib/api/thread-documents';

vi.mock('@/lib/api/thread-documents', () => ({
  listThreadDocuments: vi.fn(),
  uploadThreadDocument: vi.fn(),
  changeThreadDocument: vi.fn(),
  downloadThreadDocument: vi.fn(),
}));
const doc: api.ThreadDocument = {
  id: 'doc1',
  fileName: 'guide.txt',
  size: 100,
  status: 'PENDING',
  pinned: false,
  own: true,
  canPin: true,
  canUnpin: true,
  canDelete: true,
  createdAt: '2026-10-02T00:00:00Z',
};
const listing: api.ThreadDocumentsListing = {
  threadStatus: 'ACTIVE',
  canUpload: true,
  documents: [doc],
};
function open() {
  fireEvent.click(screen.getByRole('button', { name: /방 문서/ }));
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listThreadDocuments).mockResolvedValue(listing);
  vi.mocked(api.changeThreadDocument).mockResolvedValue();
});
afterEach(cleanup);

describe('방 문서 실제 UI', () => {
  it.each([
    ['PENDING', '처리 대기'],
    ['PROCESSING', '처리 중'],
    ['READY', '검색 준비 완료'],
    ['FAILED', '처리 실패'],
  ] as const)('처리 상태 %s를 %s로 구분해 표시한다', async (status, label) => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      documents: [{ ...doc, status }],
    });
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText(new RegExp(label));
  });
  it('draft는 서버 조회·업로드 없이 기존 첨부 안내만 표시한다', () => {
    render(<ThreadDocuments threadId="draft" available={false} />);
    open();
    expect(screen.getByText(/첫 메시지를 보내/)).toBeTruthy();
    expect(api.listThreadDocuments).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
  });
  it('열린 실제 방에서 처리 상태를 표시하고 등록은 고정과 분리한다', async () => {
    vi.mocked(api.uploadThreadDocument).mockResolvedValue(doc);
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const file = new File(['text'], 'new.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledWith(
        'room',
        expect.any(String),
        file,
      ),
    );
    expect(api.changeThreadDocument).not.toHaveBeenCalled();
    expect(screen.getByText(/처리 대기/)).toBeTruthy();
  });
  it('등록 실패 뒤 파일과 동일 등록 UUID를 유지한다', async () => {
    vi.mocked(api.uploadThreadDocument).mockRejectedValue(
      new Error('저장 실패'),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const file = new File(['text'], 'new.txt');
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await screen.findByText('저장 실패');
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledTimes(2),
    );
    expect(vi.mocked(api.uploadThreadDocument).mock.calls[0][1]).toEqual(
      vi.mocked(api.uploadThreadDocument).mock.calls[1][1],
    );
  });
  it('삭제는 확인 후 실행하고 성공 뒤 조회 실패를 저장 실패로 처리하지 않는다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    expect(api.changeThreadDocument).not.toHaveBeenCalled();
    vi.mocked(api.listThreadDocuments).mockRejectedValue(
      new Error('조회 실패'),
    );
    fireEvent.click(screen.getByRole('button', { name: '삭제 확인' }));
    await screen.findByText(/변경은 저장됐지만/);
    expect(api.changeThreadDocument).toHaveBeenCalledTimes(1);
    expect(api.changeThreadDocument).toHaveBeenCalledWith(
      'room',
      'doc1',
      'delete',
      expect.any(String),
    );
  });
  it('읽기 전용 방에서는 원본만 제공하고 등록·고정·삭제를 숨긴다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      canUpload: false,
      documents: [{ ...doc, canPin: false, canUnpin: false, canDelete: false }],
    });
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    expect(screen.getByText('이 방은 읽기 전용입니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '고정' })).toBeNull();
    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
    expect(screen.getByRole('button', { name: '원본' })).toBeTruthy();
  });
  it('늦은 이전 방 조회가 현재 방 목록을 덮지 않는다', async () => {
    let resolveOld!: (value: api.ThreadDocumentsListing) => void;
    vi.mocked(api.listThreadDocuments).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    const { rerender } = render(<ThreadDocuments threadId="old" />);
    open();
    rerender(<ThreadDocuments threadId="new" />);
    await screen.findByText('guide.txt');
    await act(async () =>
      resolveOld({ ...listing, documents: [{ ...doc, fileName: 'old.txt' }] }),
    );
    expect(screen.queryByText('old.txt')).toBeNull();
  });
  it('현재 권한을 서버가 회수하면 기존 목록과 작업 버튼을 제거한다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    vi.mocked(api.listThreadDocuments).mockRejectedValue(
      new Error('접근 불가'),
    );
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await screen.findByText('접근 불가');
    expect(screen.queryByText('guide.txt')).toBeNull();
  });
  it('중복 제출을 같은 이벤트 회차에서도 차단한다', async () => {
    vi.mocked(api.changeThreadDocument).mockImplementation(
      () => new Promise(() => {}),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const pin = screen.getByRole('button', { name: '고정' });
    fireEvent.click(pin);
    fireEvent.click(pin);
    expect(api.changeThreadDocument).toHaveBeenCalledTimes(1);
  });
});
