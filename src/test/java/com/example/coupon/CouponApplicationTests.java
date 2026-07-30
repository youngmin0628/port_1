package com.example.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CouponApplicationTests {

	@Container
	@ServiceConnection
	static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 마이그레이션이_적용되고_엔티티_매핑이_스키마와_일치한다() {
		List<String> tables = jdbcTemplate.queryForList(
				"select table_name from information_schema.tables where table_schema = database()",
				String.class);

		assertThat(tables).contains("coupon", "coupon_issue");
	}
}
