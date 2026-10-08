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
		// HTTP/1.1로 고정한다. JDK HttpClient의 기본(HTTP/2)은 http:// 주소에 h2c 업그레이드를 시도하는데, 사내 임베딩 서버(vLLM·uvicorn)는
		// 그 요청의 본문을 받지 못해 "body Field required"로 거절한다.
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10)).build());
		factory.setReadTimeout(timeout);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	static EtlFailure classify(String code, RuntimeException error) {
		if (error instanceof EtlFailure failure) return failure;
		if (error instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			// 408은 프록시·게이트웨이 시간 초과라 일시 장애다. 401·403은 내부 key·권한 설정 문제라 문서 탓이 아니다 — 영구 실패로 두면
			// 설정을 고친 뒤에도 문서마다 재처리해야 하므로 일시 장애로 재시도하고, 호출부 로그(error)로 운영자가 알게 한다.
			if (status >= 500 || status == 429 || status == 408)
				return EtlFailure.transientFailure(code + "_UNAVAILABLE", "HTTP " + status, error);
			if (status == 401 || status == 403)
				return EtlFailure.transientFailure(code + "_UNAUTHORIZED", "HTTP " + status + " — 내부 key·권한 설정 확인 필요", error);
			return EtlFailure.permanent(code + "_REJECTED", "HTTP " + status + " " + abbreviate(response.getResponseBodyAsString()), error);
		}
		return EtlFailure.transientFailure(code + "_UNAVAILABLE", String.valueOf(error.getMessage()), error);
	}

	static String abbreviate(String body) {
		return body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
	}
}
