package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("pessimistic")
public class PessimisticLockCouponIssuer implements CouponIssuer {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	public PessimisticLockCouponIssuer(CouponRepository couponRepository,
			CouponIssueRepository couponIssueRepository) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
	}

	// 쿠폰 행을 잠가 같은 쿠폰에 대한 발급을 직렬화한다.
	// count()를 issued_quantity 컬럼으로 바꾸지 않은 것은 의도적이다 - 나이브 구현과
	// 유일하게 다른 점이 락이어야 TPS 차이를 락의 대가로 읽을 수 있다.
	@Override
	@Transactional
	public boolean issue(Long couponId, Long userId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow();

		long issued = couponIssueRepository.countByCouponId(couponId);
		if (issued >= coupon.getTotalQuantity()) {
			return false;
		}

		couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
		return true;
	}
}
