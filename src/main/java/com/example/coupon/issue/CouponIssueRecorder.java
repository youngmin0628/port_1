package com.example.coupon.issue;

import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// DB 쓰기만 담당한다. Redis 호출과 트랜잭션 경계를 클래스로 갈라 두면
// "트랜잭션 안에서 외부 호출을 하지 않는다"가 코드 구조로 보장된다.
@Component
@Profile("redis")
public class CouponIssueRecorder {

	private final CouponIssueRepository couponIssueRepository;

	public CouponIssueRecorder(CouponIssueRepository couponIssueRepository) {
		this.couponIssueRepository = couponIssueRepository;
	}

	@Transactional
	public void record(Long couponId, Long userId) {
		couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
	}
}
