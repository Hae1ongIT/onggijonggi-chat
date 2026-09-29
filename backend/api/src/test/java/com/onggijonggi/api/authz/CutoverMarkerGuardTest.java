package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Class Name : CutoverMarkerGuardTest.java
 * Description : 절체 표지가 있는 DB에서 비강제 기동을 차단하고, 표지가 없는 DB는 기존 동작을 유지한다.
 */
class CutoverMarkerGuardTest {

	@Test
	void absentTableOrMarkerDoesNotForceEnforcement() {
		JdbcTemplate jdbc = database();
		RbacProperties properties = new RbacProperties();
		new CutoverMarkerGuard(jdbc, properties).run(null);
		jdbc.execute("create table ctv (id smallint primary key)");
		new CutoverMarkerGuard(jdbc, properties).run(null);
	}

	@Test
	void markerRejectsDisabledEnforcementButAllowsEnabledEnforcement() {
		JdbcTemplate jdbc = database();
		jdbc.execute("create table ctv (id smallint primary key)");
		jdbc.update("insert into ctv (id) values (1)");
		RbacProperties properties = new RbacProperties();
		assertThatThrownBy(() -> new CutoverMarkerGuard(jdbc, properties).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("app.rbac.enforce=false");
		properties.setEnforce(true);
		new CutoverMarkerGuard(jdbc, properties).run(null);
	}

	private static JdbcTemplate database() {
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setDriverClassName("org.h2.Driver");
		source.setUrl("jdbc:h2:mem:cutover_marker_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
		return new JdbcTemplate(source);
	}
}
