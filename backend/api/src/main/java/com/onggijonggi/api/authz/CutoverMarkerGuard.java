package com.onggijonggi.api.authz;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Class Name : CutoverMarkerGuard.java
 * Description : 완료 표지가 기록된 DB에서 RBAC 강제를 끄고 서버가 기동되는 것을 막는다.
 */
@Component
public class CutoverMarkerGuard implements ApplicationRunner {

	private final JdbcTemplate jdbc;
	private final RbacProperties properties;

	public CutoverMarkerGuard(JdbcTemplate jdbc, RbacProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isEnforce() && hasCutoverMarker()) {
			throw new IllegalStateException("절체 완료 DB는 app.rbac.enforce=false로 기동할 수 없습니다");
		}
	}

	boolean hasCutoverMarker() {
		// 앱이 쓰는 schema(search_path)의 ctv만 본다. 테이블이 없으면 표지도 없다.
		// 권한 오류 같은 다른 조회 실패는 삼키지 않는다 — 표지를 읽지 못한 채 기동하지 않는다(fail-closed).
		try {
			Integer count = jdbc.queryForObject("select count(*) from ctv", Integer.class);
			return count != null && count > 0;
		} catch (BadSqlGrammarException tableMissing) {
			return false;
		}
	}
}
