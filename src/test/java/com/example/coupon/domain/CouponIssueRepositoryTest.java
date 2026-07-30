package com.example.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.support.MySqlTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponIssueRepositoryTest extends MySqlTestBase {

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 발급_이력을_쿠폰별로_센다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 100장", 100));
		Coupon other = couponRepository.save(new Coupon("다른 쿠폰", 100));

		couponIssueRepository.save(new CouponIssue(coupon.getId(), 1L, LocalDateTime.now()));
		couponIssueRepository.save(new CouponIssue(coupon.getId(), 2L, LocalDateTime.now()));
		couponIssueRepository.save(new CouponIssue(other.getId(), 3L, LocalDateTime.now()));

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
	}
}
