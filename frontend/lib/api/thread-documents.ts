/** Thread 문서는 사용자 토큰으로 BFF만 호출한다. 재시도에도 등록·변경 식별자는 유지한다. */
import { bffUrl } from './config';
import { authFetch } from './http';

export interface ThreadDocument {
  id: string;
  fileName: string;
  size: number;
  status: 'UPLOADING' | 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';
  pinned: boolean;
  own: boolean;
  canPin: boolean;
  canUnpin: boolean;
  canDelete: boolean;
  canReadOriginal: boolean;
  createdAt: string;
}
export interface ThreadDocumentsListing {
  threadStatus: string;
  canUpload: boolean;
  documents: ThreadDocument[];
}
const path = (thread: string) =>
  `/api/threads/${encodeURIComponent(thread)}/documents`;

async function fetchDocument(
  url: string,
  init?: RequestInit,
): Promise<Response> {
  try {
    return await authFetch(url, init);
  } catch (cause) {
    if (cause instanceof TypeError)
      throw new Error(
        '문서 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해주세요.',
      );
    throw cause;
  }
}

async function checked(response: Response): Promise<Response> {
  if (response.ok) return response;
  const messages: Record<number, string> = {
    400: '파일과 입력값을 확인해주세요.',
    401: '다시 로그인해주세요.',
    403: '이 문서를 변경할 권한이 없습니다.',
    404: '방이나 문서에 접근할 수 없습니다.',
    409: '현재 방 또는 문서 상태에서는 변경할 수 없습니다. 목록을 다시 조회해주세요.',
    413: '파일은 10MB 이하만 등록할 수 있습니다.',
    415: 'TXT, MD, CSV, PDF, DOCX 파일만 등록할 수 있습니다.',
    503: '원본 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해주세요.',
  };
  throw new Error(messages[response.status] ?? '문서 요청에 실패했습니다.');
}
export async function listThreadDocuments(
  thread: string,
  signal?: AbortSignal,
): Promise<ThreadDocumentsListing> {
  const response = await checked(
    await fetchDocument(bffUrl(path(thread)), { signal, cache: 'no-store' }),
  );
  return response.json();
}
export async function uploadThreadDocument(
  thread: string,
  id: string,
  file: File,
): Promise<ThreadDocument> {
  const body = new FormData();
  body.append('file', file);
  const response = await checked(
    await fetchDocument(bffUrl(`${path(thread)}?documentId=${id}`), {
      method: 'POST',
      body,
    }),
  );
  return response.json();
}
export async function changeThreadDocument(
  thread: string,
  id: string,
  action: 'pin' | 'unpin' | 'delete',
  requestId: string,
): Promise<void> {
  const base = `${path(thread)}/${encodeURIComponent(id)}`;
  await checked(
    await fetchDocument(
      bffUrl(
        action === 'delete' ? `${base}?requestId=${requestId}` : `${base}/pin`,
      ),
      action === 'delete'
        ? { method: 'DELETE' }
        : {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ requestId, pinned: action === 'pin' }),
          },
    ),
  );
}
export async function downloadThreadDocument(thread: string, id: string) {
  const response = await checked(
    await fetchDocument(
      bffUrl(`${path(thread)}/${encodeURIComponent(id)}/original`),
      { cache: 'no-store' },
    ),
  );
  return response.blob();
}
