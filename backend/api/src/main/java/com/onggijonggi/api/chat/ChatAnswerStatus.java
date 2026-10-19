package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Class Name : ChatAnswerStatus.java
 * Description : ChatAnswerFrame의 진행 상태. 프론트 WsFrame(frontend/lib/transport/frames.ts)의
 *               status 리터럴 유니온과 JSON 표현이 정확히 같아야 한다. DENIED는 DIRECT 전용이다(이슈 #162).
 *               CANCELLED는 사람이 멈춘 답(협업방도, 이슈 #413), FAILED는 실패한 답이다(이슈 #413 결정 8) — 실패를 오류
 *               프레임만으로 알리면 그 프레임에 msgId가 없어 화면이 어느 답이 실패했는지 몰랐다. 옛 화면은 모르는 상태라
 *               이 프레임을 버리고 지금처럼 오류 프레임으로 본다.
 */
public enum ChatAnswerStatus {

	@JsonProperty("streaming") STREAMING,
	@JsonProperty("done") DONE,
	@JsonProperty("cancelled") CANCELLED,
	@JsonProperty("denied") DENIED,
	@JsonProperty("failed") FAILED

}
