package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChatAnswerFrameTest.java
 * Description : 답변 프레임의 받는 사람별 mine 채우기와, 서버 안에서만 드는 요청자 subject가 직렬화되지 않는 것을
 *               검증한다(이슈 #413).
 */
class ChatAnswerFrameTest {

	private static ChatAnswerFrame streaming(String requesterSubject) {
		return new ChatAnswerFrame(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "m", 1L, "조각", List.of(), false,
				ChatAnswerStatus.STREAMING, null, null, 0, null, null, requesterSubject);
	}

	@Test
	void fillsMineForEachRecipientOnlyWhenTheRequesterIsKnown() {
		ChatAnswerFrame frame = streaming("requester");

		assertThat(frame.forRecipient("requester").mine()).isTrue();
		assertThat(frame.forRecipient("someone-else").mine()).isFalse();
		assertThat(frame.forRecipient("requester")).usingRecursiveComparison().ignoringFields("mine").isEqualTo(frame);
		ChatAnswerFrame unknown = streaming(null);
		assertThat(unknown.forRecipient("requester")).isSameAs(unknown);
	}

	@Test
	void neverSerializesTheRequesterSubject() {
		String json = new JsonMapper().writeValueAsString(streaming("secret-subject").forRecipient("secret-subject"));

		assertThat(json).doesNotContain("secret-subject").doesNotContain("requesterSubject").contains("\"mine\":true")
				.contains("\"offset\":0");
	}
}
