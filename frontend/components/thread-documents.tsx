'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  changeThreadDocument,
  downloadThreadDocument,
  listThreadDocuments,
  uploadThreadDocument,
  type ThreadDocumentsListing,
} from '@/lib/api/thread-documents';

const statuses: Record<string, string> = {
  UPLOADING: '원본 저장 중',
  PENDING: '처리 대기',
  PROCESSING: '처리 중',
  READY: '검색 준비 완료',
  FAILED: '처리 실패',
};

/** 일회성 첨부와 별도로, 생성된 방에 남기는 검색용 문서의 등록·고정을 관리한다. */
export function ThreadDocuments({
  threadId,
  available = true,
}: { threadId: string; available?: boolean }) {
  const [open, setOpen] = useState(false);
  const [listing, setListing] = useState<ThreadDocumentsListing | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const sequence = useRef(0);
  const alive = useRef(true);
  const writing = useRef(false);
  const uploadId = useRef<string | null>(null);
  const changes = useRef(
    new Map<string, { action: 'pin' | 'unpin' | 'delete'; id: string }>(),
  );
  const scope = useRef(0);
  const reading = useRef(false);
  const request = useRef<AbortController | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    const version = ++sequence.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    reading.current = true;
    try {
      const next = await listThreadDocuments(threadId, controller.signal);
      if (alive.current && version === sequence.current) {
        setListing(next);
        setConfirmation((id) =>
          id && next.documents.some((doc) => doc.id === id && doc.canDelete)
            ? id
            : null,
        );
        setError('');
      }
    } catch (cause) {
      if (alive.current && version === sequence.current) {
        setListing(null);
        setConfirmation(null);
        setError(
          cause instanceof Error ? cause.message : '목록 조회에 실패했습니다.',
        );
      }
      throw cause;
    } finally {
      if (version === sequence.current) reading.current = false;
    }
  }, [threadId]);

  useEffect(() => {
    alive.current = true;
    setListing(null);
    setFile(null);
    setConfirmation(null);
    setError('');
    setBusy(false);
    writing.current = false;
    uploadId.current = null;
    changes.current.clear();
    return () => {
      alive.current = false;
      scope.current += 1;
      request.current?.abort();
      reading.current = false;
      sequence.current += 1;
    };
  }, [threadId]);
  useEffect(() => {
    if (!open || !available) return;
    void refresh().catch(() => {});
    const timer = setInterval(() => {
      if (!writing.current && !reading.current) void refresh().catch(() => {});
    }, 10_000);
    return () => {
      clearInterval(timer);
      sequence.current += 1;
      request.current?.abort();
      reading.current = false;
    };
  }, [open, available, refresh]);

  async function mutate(action: () => Promise<unknown>, uploaded = false) {
    if (writing.current) return;
    writing.current = true;
    request.current?.abort();
    reading.current = false;
    sequence.current += 1;
    const generation = scope.current;
    setBusy(true);
    setError('');
    try {
      await action();
      if (!alive.current || generation !== scope.current) return;
      setConfirmation(null);
      if (uploaded) {
        setFile(null);
        uploadId.current = null;
        if (input.current) input.current.value = '';
      }
      try {
        await refresh();
      } catch {
        if (alive.current && generation === scope.current)
          setError(
            '변경은 저장됐지만 목록을 갱신하지 못했습니다. 목록을 다시 조회해주세요.',
          );
      }
    } catch (cause) {
      if (alive.current && generation === scope.current)
        setError(
          cause instanceof Error ? cause.message : '문서 변경에 실패했습니다.',
        );
    } finally {
      if (alive.current && generation === scope.current) {
        writing.current = false;
        setBusy(false);
      }
    }
  }

  async function download(id: string, name: string) {
    const generation = scope.current;
    try {
      const blob = await downloadThreadDocument(threadId, id);
      if (!alive.current || generation !== scope.current) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      link.click();
      // 같은 틱에 해제하면 일부 브라우저가 내려받기를 시작하기 전에 취소한다.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (cause) {
      if (alive.current && generation === scope.current)
        setError(
          cause instanceof Error ? cause.message : '원본을 읽지 못했습니다.',
        );
    }
  }

  function change(id: string, action: 'pin' | 'unpin' | 'delete') {
    if (writing.current) return;
    const pending = changes.current.get(id);
    const operation =
      pending?.action === action
        ? pending
        : { action, id: crypto.randomUUID() };
    changes.current.set(id, operation);
    void mutate(async () => {
      await changeThreadDocument(threadId, id, action, operation.id);
      if (changes.current.get(id) === operation) changes.current.delete(id);
    });
  }

  return (
    <section className="border-b px-4 py-2 text-sm" aria-label="방 문서">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? '▾' : '▸'} 방 문서 · 등록 및 고정
      </button>
      {open && !available && (
        <p className="mt-2 text-muted-foreground">
          첫 메시지를 보내 방을 만든 뒤 등록할 수 있습니다. 일회성 첨부는
          입력창에서 사용해주세요.
        </p>
      )}
      {open && available && (
        <div className="mt-3 space-y-3">
          <p className="text-muted-foreground">
            등록만으로 AI가 사용하지 않습니다. 고정된 문서 중 처리가 완료된
            문서만 검색 대상입니다.
          </p>
          <button
            type="button"
            disabled={busy}
            onClick={() => void refresh().catch(() => {})}
          >
            목록 다시 조회
          </button>
          {error && (
            <p role="alert" className="text-destructive">
              {error}
            </p>
          )}
          {listing && !listing.canUpload && <p>이 방은 읽기 전용입니다.</p>}
          {listing?.canUpload && (
            <form
              className="flex flex-wrap items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                if (!file) return;
                const id = uploadId.current ?? crypto.randomUUID();
                uploadId.current = id;
                void mutate(
                  () => uploadThreadDocument(threadId, id, file),
                  true,
                );
              }}
            >
              <input
                ref={input}
                type="file"
                aria-label="등록할 방 문서"
                accept=".txt,.md,.csv,.pdf,.docx"
                disabled={busy}
                onChange={(event) => {
                  const chosen = event.target.files?.[0] ?? null;
                  setFile(chosen);
                  uploadId.current = null;
                }}
              />
              <button type="submit" disabled={busy || !file}>
                문서 등록
              </button>
              <span className="text-muted-foreground">
                최대 10MB · TXT/MD/CSV/PDF/DOCX
              </span>
            </form>
          )}
          {listing?.documents.length === 0 && <p>등록된 문서가 없습니다.</p>}
          <ul className="space-y-2 max-h-64 overflow-y-auto">
            {listing?.documents.map((doc) => (
              <li
                key={doc.id}
                className="rounded-md border p-3 flex flex-wrap items-center gap-3"
              >
                <div className="min-w-0 flex-1">
                  <span className="break-all">{doc.fileName}</span>
                  <p className="text-xs text-muted-foreground">
                    {statuses[doc.status]} · {Math.ceil(doc.size / 1024)}KB
                    {doc.pinned ? ' · 고정됨' : ''}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={busy || !doc.canReadOriginal}
                  onClick={() => void download(doc.id, doc.fileName)}
                >
                  원본
                </button>
                {(doc.pinned ? doc.canUnpin : doc.canPin) && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => change(doc.id, doc.pinned ? 'unpin' : 'pin')}
                  >
                    {doc.pinned ? '고정 해제' : '고정'}
                  </button>
                )}
                {doc.canDelete && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => setConfirmation(doc.id)}
                  >
                    삭제
                  </button>
                )}
              </li>
            ))}
          </ul>
          {confirmation && (
            <div
              role="dialog"
              aria-label="방 문서 삭제 확인"
              className="rounded-md border p-3"
            >
              <p>
                문서를 삭제하면 이후 답변에서 사용할 수 없으며 원본도
                정리됩니다.
              </p>
              <button
                type="button"
                disabled={busy}
                onClick={() => setConfirmation(null)}
              >
                취소
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => change(confirmation, 'delete')}
              >
                삭제 확인
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
