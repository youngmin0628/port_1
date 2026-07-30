package com.example.coupon.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

public abstract class MySqlTestBase {

	// @Container를 쓰지 않고 직접 start()한다. JUnit의 Testcontainers 확장은 테스트 클래스마다
	// 컨테이너를 stop/start 하므로, 클래스가 여러 개면 MySQL도 그만큼 뜬다.
	// 정리는 Ryuk이 JVM 종료 시 맡는다.
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	static {
		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}
}
