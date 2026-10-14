package com.onggijonggi.etl;

import com.onggijonggi.common.document.ThreadSourcePaths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Class Name : SourceReader.java
 * Description : 등록된 원본을 문서 워커의 내부 원본 API로 읽는다. SeaweedFS를 직접 읽지 않는다 — 원본 위치 규칙은 문서 워커만 안다.
 *               받은 바이트의 SHA-256이 등록 때 digest와 다르거나 원본이 없으면 다시 읽어도 같으므로 영구 실패다.
 */
@Component
public class SourceReader {

	private final RestClient client;
	private final String apiKey;

	public SourceReader(EtlProperties properties) {
		this.client = HttpCalls.client(properties.worker().url(), properties.requestTimeout());
		this.apiKey = properties.worker().apiKey();
	}

	public byte[] read(RunStore.Job job) {
		if (apiKey.isBlank()) throw EtlFailure.transientFailure("SOURCE_UNAVAILABLE", "문서 워커 내부 key(app.etl.worker.api-key)가 비어 있다", null);
		byte[] bytes;
		try {
			bytes = client.get().uri(ThreadSourcePaths.path(job.thread(), job.document(), job.digest()))
					.header(ThreadSourcePaths.API_KEY_HEADER, apiKey)
					.header(ThreadSourcePaths.TENANT_HEADER, job.tenant().toString())
					.headers(headers -> { if (job.sourceAttempt() != null) headers.set(ThreadSourcePaths.ATTEMPT_HEADER, job.sourceAttempt().toString()); })
					.retrieve().body(byte[].class);
		} catch (HttpClientErrorException.NotFound missing) {
			throw EtlFailure.permanent("SOURCE_MISSING", "원본이 없다", missing);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("SOURCE", error);
		}
		if (bytes == null || !sha256(bytes).equals(job.digest())) throw EtlFailure.permanent("SOURCE_MISSING", "원본 digest 불일치");
		return bytes;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}
}
