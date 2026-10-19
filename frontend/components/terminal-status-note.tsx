/********************************************************
 파일명 : terminal-status-note.tsx (components)
 설 명 : AI 답변이 평범하게 끝나지 않았을 때 말풍선에 붙이는 한 줄 — 중지·거부·실패. 1:1(message.tsx)과
 협업방(collab-room.tsx)이 같은 문구를 쓴다(이슈 #413 결정 11). 예전에는 1:1에만 있어 협업방에서는 멈춘 답이
 받은 데까지만, 빈 채 멈춘 답은 빈 말풍선으로 보였다. 실패는 사람이 멈춘 것이 아니라(시간 초과·모델 오류·서버
 종료) "중단"과 문구로 가른다(결정 8·10).
 *********************************************************/

import type { TerminalStatus } from '@/lib/chat/room-state';
import { cn } from '@/lib/utils';

const NOTE: Record<TerminalStatus, { text: string; tone: string }> = {
  cancelled: {
    text: '응답이 중단되었습니다.',
    tone: 'text-muted-foreground',
  },
  denied: {
    text: '요청이 많아 이번 응답은 처리되지 않았습니다. 다시 시도해 주세요.',
    tone: 'text-destructive',
  },
  failed: {
    text: '답변을 만들지 못했습니다.',
    tone: 'text-destructive',
  },
};

/** 본문이 있으면 그 아래에 덧붙고, 없으면(빈 채 멈춤·거부·실패) 이 줄이 유일한 표시가 된다. */
export function TerminalStatusNote({
  status,
  className,
}: {
  status: TerminalStatus | null | undefined;
  className?: string;
}) {
  if (!status) return null;
  const note = NOTE[status];
  return (
    <span className={cn('text-sm', note.tone, className)}>{note.text}</span>
  );
}
