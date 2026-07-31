package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("redis")
class RedisDuplicateIssueTest extends DuplicateIssueTestBase {

	// 정합성만 보면 나이브도 1건으로 통과한다. 다른 것은 비용이다.
	// 나이브는 999번을 DB까지 보내 제약 위반으로 튕기고, Redis는 Set에서 먼저 거른다.
	// 예외 0건이 그 차이를 보여주는 수치다.
	@Test
	void 중복_요청이_DB까지_가지_않는다() throws InterruptedException {
		assertThat(중복_요청을_실행한다()).isEqualTo(1);
		assertThat(exceptions).hasValue(0);
	}
}
