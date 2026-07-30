package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponIssueService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	public CouponIssueService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
	}

	// 락이 없다. count()로 읽은 발급 수는 save()가 커밋될 때까지 다른 트랜잭션에게
	// 보이지 않으므로, 동시에 들어온 요청들이 모두 같은 수를 읽고 모두 통과한다.
	// Phase 1은 이 문제를 재현하는 것이 목적이므로 고치지 않는다.
	@Transactional
	public boolean issue(Long couponId, Long userId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow();

		long issued = couponIssueRepository.countByCouponId(couponId);
		if (issued >= coupon.getTotalQuantity()) {
			return false;
		}

		couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
		return true;
	}
}
