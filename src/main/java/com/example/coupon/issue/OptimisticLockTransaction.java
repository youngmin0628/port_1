package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("optimistic")
public class OptimisticLockTransaction {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	public OptimisticLockTransaction(CouponRepository couponRepository,
			CouponIssueRepository couponIssueRepository) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
	}

	// REQUIRES_NEW로 매 시도마다 새 트랜잭션을 연다. 재시도는 호출자가 담당한다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean attempt(Long couponId, Long userId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow();

		if (!coupon.tryIssue()) {
			return false;
		}

		couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
		return true;
	}
}
