package com.example.coupon.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

public abstract class ContainerTestBase {

	private static final int REDIS_PORT = 6379;

	// @Container를 쓰지 않고 직접 start()한다. JUnit의 Testcontainers 확장은 테스트 클래스마다
	// 컨테이너를 stop/start 하므로, 클래스가 여러 개면 그만큼 뜬다.
	// 정리는 Ryuk이 JVM 종료 시 맡는다.
	protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	// Redis를 안 쓰는 테스트도 이 컨테이너를 띄운다. StockRaceTestBase가 이미 이 클래스를
	// 상속하고 있어 Redis 전용 베이스를 따로 둘 수 없고, alpine 이미지라 비용이 무시할 수준이다.
	private static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7.4-alpine").withExposedPorts(REDIS_PORT);

	static {
		MYSQL.start();
		REDIS.start();
	}

	@Autowired
	private StringRedisTemplate redisTemplate;

	// 컨테이너를 테스트 클래스 전체가 공유하므로 앞선 테스트가 남긴 카운터와 Set을 지워야 한다.
	// 빠뜨리면 다음 테스트가 첫 요청부터 품절이 된다.
	@BeforeEach
	void Redis를_비운다() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
	}

	@DynamicPropertySource
	static void 컨테이너_접속정보(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
	}
}
