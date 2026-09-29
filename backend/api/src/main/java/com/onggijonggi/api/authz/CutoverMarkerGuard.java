package com.onggijonggi.api.authz;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
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
		Boolean exists = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
			DatabaseMetaData metadata = connection.getMetaData();
			try (ResultSet tables = metadata.getTables(null, null, null, new String[] {"TABLE"})) {
				while (tables.next()) {
					if ("ctv".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
				}
			}
			return false;
		});
		if (!Boolean.TRUE.equals(exists)) return false;
		Integer count = jdbc.queryForObject("select count(*) from ctv", Integer.class);
		return count != null && count > 0;
	}
}
