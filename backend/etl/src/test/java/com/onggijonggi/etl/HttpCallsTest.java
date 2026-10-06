package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Class Name : HttpCallsTest.java
 * Description : 외부 호출 실패의 일시/영구 분류. 설정 문제(401·403)와 게이트웨이 시간 초과(408)는 문서 탓이 아니라 다시 시도한다.
 */
class HttpCallsTest {

	private static EtlFailure classify(HttpStatus status) {
		return HttpCalls.classify("SOURCE", HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
				"{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
	}

	@Test
	void timeoutsAndConfigurationErrorsAreRetriedButBadRequestsAreNot() {
		assertThat(classify(HttpStatus.REQUEST_TIMEOUT).permanent()).isFalse();
		assertThat(classify(HttpStatus.TOO_MANY_REQUESTS).permanent()).isFalse();
		assertThat(classify(HttpStatus.UNAUTHORIZED)).satisfies(failure -> {
			assertThat(failure.permanent()).isFalse();
			assertThat(failure.code()).isEqualTo("SOURCE_UNAUTHORIZED");
		});
		assertThat(classify(HttpStatus.FORBIDDEN).permanent()).isFalse();
		assertThat(classify(HttpStatus.BAD_REQUEST)).satisfies(failure -> {
			assertThat(failure.permanent()).isTrue();
			assertThat(failure.code()).isEqualTo("SOURCE_REJECTED");
		});
		assertThat(HttpCalls.classify("SOURCE", new ResourceAccessException("connection refused")).permanent()).isFalse();
	}
}
