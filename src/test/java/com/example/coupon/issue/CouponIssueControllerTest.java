package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.MySqlTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("naive")
class CouponIssueControllerTest extends MySqlTestBase {

	@Autowired
	private TestRestTemplate restTemplate;

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
	void 발급에_성공하면_200을_반환한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 1장", 1));

		ResponseEntity<Void> response = restTemplate.postForEntity(
				"/coupons/{couponId}/issues", Map.of("userId", 1), Void.class, coupon.getId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void 품절이면_409를_반환한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 1장", 1));
		restTemplate.postForEntity("/coupons/{couponId}/issues", Map.of("userId", 1), Void.class, coupon.getId());

		ResponseEntity<Void> response = restTemplate.postForEntity(
				"/coupons/{couponId}/issues", Map.of("userId", 2), Void.class, coupon.getId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}
}
