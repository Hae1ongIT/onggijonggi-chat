package com.onggijonggi.api.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadSourceStorage.java
 * Description : 방 인가가 끝난 원본만 내부 워커로 전달한다. 브라우저에는 내부 URL·API key를 노출하지 않는다.
 */
@Component
public class ThreadSourceStorage {
	private final WebClient client;
	private final String apiKey;

	public ThreadSourceStorage(WebClient.Builder builder,
			@Value("${app.document.worker-url:http://localhost:8100}") String workerUrl,
			@Value("${app.document.internal-api-key:}") String apiKey) {
		this.apiKey = apiKey;
		this.client = builder.clone().baseUrl(workerUrl)
				.codecs(config -> config.defaultCodecs().maxInMemorySize(ThreadDocumentService.MAX_FILE_BYTES + 1024)).build();
	}

	private String path(UUID thread, UUID document, String digest) {
		if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		return "/api/v1/thread-sources/" + thread + "/" + document + "/" + digest;
	}

	public void save(UUID tenant, UUID thread, UUID document, String digest, byte[] content, Instant expiresAt, UUID attempt) {
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(content) {
			@Override public String getFilename() { return "source"; }
		});
		try {
			client.put().uri(path(thread, document, digest))
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.header("X-Source-Expires-At", Long.toString(expiresAt.getEpochSecond()))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body)
					.retrieve().toBodilessEntity().block(Duration.ofSeconds(60));
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	public byte[] read(UUID tenant, UUID thread, UUID document, String digest, UUID attempt) {
		try {
			byte[] content = client.get().uri(path(thread, document, digest))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(60));
			if (content == null) throw new IllegalStateException("원본 응답이 비어 있다");
			return content;
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	public void delete(UUID tenant, UUID thread, UUID document, String digest, UUID attempt) {
		try {
			client.delete().uri(path(thread, document, digest))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.retrieve().toBodilessEntity().block(Duration.ofSeconds(60));
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	private ResponseStatusException unavailable(RuntimeException error) {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "문서 저장소를 사용할 수 없습니다", error);
	}
}
