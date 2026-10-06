package com.onggijonggi.etl;

/**
 * Class Name : EtlFailure.java
 * Description : 처리 단계의 실패. permanent는 다시 해도 같은 결과(손상 파일·글자 없음·계약 불일치)라 바로 FAILED로 두고,
 *               아니면 일시 장애(연결·시간 초과·5xx·429·408, 내부 key·권한 설정 401·403)라 간격을 두고 다시 시도한다. code는 thr_doc_run.err에만 남고
 *               사용자에게는 보이지 않는다.
 */
public class EtlFailure extends RuntimeException {

	private final String code;
	private final boolean permanent;

	private EtlFailure(String code, boolean permanent, String message, Throwable cause) {
		super(code + ": " + message, cause);
		this.code = code;
		this.permanent = permanent;
	}

	public static EtlFailure permanent(String code, String message) {
		return new EtlFailure(code, true, message, null);
	}

	public static EtlFailure permanent(String code, String message, Throwable cause) {
		return new EtlFailure(code, true, message, cause);
	}

	public static EtlFailure transientFailure(String code, String message, Throwable cause) {
		return new EtlFailure(code, false, message, cause);
	}

	public String code() {
		return code;
	}

	public boolean permanent() {
		return permanent;
	}
}
