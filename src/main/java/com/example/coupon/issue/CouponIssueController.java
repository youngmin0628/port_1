package com.example.coupon.issue;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponIssueController {

	private final CouponIssueService couponIssueService;

	public CouponIssueController(CouponIssueService couponIssueService) {
		this.couponIssueService = couponIssueService;
	}

	@PostMapping("/coupons/{couponId}/issues")
	public ResponseEntity<Void> issue(@PathVariable Long couponId, @RequestBody CouponIssueRequest request) {
		// k6가 발급과 품절을 상태코드로 구분해야 한다. 둘 다 200이면 실제 발급률을 알 수 없다.
		if (couponIssueService.issue(couponId, request.userId())) {
			return ResponseEntity.ok().build();
		}
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}
}
