package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponIssueServiceTest extends MySqlTestBase {

	@Autowired
	private CouponIssueService couponIssueService;

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
	void 재고가_남아있으면_발급한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 2장", 2));

		boolean issued = couponIssueService.issue(coupon.getId(), 1L);

		assertThat(issued).isTrue();
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void 재고가_소진되면_발급하지_않는다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 2장", 2));
		couponIssueService.issue(coupon.getId(), 1L);
		couponIssueService.issue(coupon.getId(), 2L);

		boolean issued = couponIssueService.issue(coupon.getId(), 3L);

		assertThat(issued).isFalse();
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
	}
}
