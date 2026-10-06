package com.onggijonggi.common.document;

import java.util.UUID;

/**
 * Class Name : ThreadSourcePaths.java
 * Description : 문서 워커의 방 문서 원본 내부 API 경로와 헤더 이름. BFF(저장·열람·정리)와 ETL 워커(읽기)가 같은 규칙을 쓴다 —
 *               원본이 실제로 놓이는 저장소 경로(Tenant·Thread·문서·digest·저장 시도)는 문서 워커만 안다.
 */
public final class ThreadSourcePaths {

	public static final String API_KEY_HEADER = "X-Internal-Api-Key";
	public static final String TENANT_HEADER = "X-Tenant-Id";
	public static final String EXPIRES_HEADER = "X-Source-Expires-At";
	/** 저장 시도 ID. 기존(시도 ID 없는) 원본은 헤더를 보내지 않는다. */
	public static final String ATTEMPT_HEADER = "X-Source-Attempt-Id";

	private ThreadSourcePaths() {
	}

	public static String path(UUID thread, UUID document, String digest) {
		return "/api/v1/thread-sources/" + thread + "/" + document + "/" + digest;
	}
}
