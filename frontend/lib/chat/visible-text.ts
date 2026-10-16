/********************************************************
 파일명 : visible-text.ts (lib/chat)
 설 명 : 밖에서 온 글자(모델이 만든 검색어, 웹 페이지 제목·요약·주소)를 화면에 그대로 보여도 되게 다듬는다.
 도구 진행 표시(이슈 #397)와 웹 출처(이슈 #401)가 함께 쓴다. 서버는 방에 내보내는 값 자체를 줄이고(제어 문자,
 길이), 화면은 보이는 모양을 지킨다 — 방향 바꾸기 문자는 뒤 문구까지 뒤집어 보이게 할 수 있다.
 *********************************************************/

/**
 * 풀고 나면 보이는 모양을 바꾸는 글자인지 — 제어 문자와 방향 바꾸기 문자(주소를 거꾸로 보이게 꾸밀 수 있다).
 * 정규식에 제어 문자를 쓰지 않으려고(lint) 코드 값으로 본다.
 */
function isDisguising(code: number): boolean {
  return (
    code <= 0x1f ||
    code === 0x7f ||
    code === 0x200e ||
    code === 0x200f ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069)
  );
}

/** 섞인 제어·방향 바꾸기 문자를 뺀다 — 남기면 앞뒤 문구까지 뒤집혀 보일 수 있다. */
export function withoutDisguising(text: string): string {
  return [...text]
    .filter((char) => !isDisguising(char.codePointAt(0) ?? 0))
    .join('');
}

/**
 * 주소의 %인코딩(한글 경로 등)을 사람이 읽을 수 있게 푼다. 풀 수 없거나, 풀면 제어·방향 바꾸기 문자가 드러나면
 * 그대로 둔다.
 */
export function readableUrl(url: string): string {
  try {
    const decoded = decodeURI(url);
    return [...decoded].some((char) => isDisguising(char.codePointAt(0) ?? 0))
      ? url
      : decoded;
  } catch {
    return url;
  }
}
