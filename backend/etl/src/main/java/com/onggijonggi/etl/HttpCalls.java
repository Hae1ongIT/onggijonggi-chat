package com.onggijonggi.etl;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Class Name : HttpCalls.java
 * Description : 외부 호출(문서 워커·임베딩·Elasticsearch)의 공통 클라이언트와 실패 분류. 연결 실패·시간 초과·5xx·429는
 *               일시 장애로, 그 밖의 4xx는 다시 해도 같은 결과라 영구 실패로 본다. 404처럼 호출부가 따로 뜻을 정하는
 *               상태는 호출부가 먼저 처리한다.
 */
final class HttpCalls {

	private HttpCalls() {
	}

	static RestClient client(String baseUrl, Duration timeout) {
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		factory.setReadTimeout(timeout);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	static EtlFailure classify(String code, RuntimeException error) {
		if (error instanceof EtlFailure failure) return failure;
		if (error instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			if (status >= 500 || status == 429)
				return EtlFailure.transientFailure(code + "_UNAVAILABLE", "HTTP " + status, error);
			return EtlFailure.permanent(code + "_REJECTED", "HTTP " + status + " " + abbreviate(response.getResponseBodyAsString()), error);
		}
		return EtlFailure.transientFailure(code + "_UNAVAILABLE", String.valueOf(error.getMessage()), error);
	}

	static String abbreviate(String body) {
		return body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
	}
}
