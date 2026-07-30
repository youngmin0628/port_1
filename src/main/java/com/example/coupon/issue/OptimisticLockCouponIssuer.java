package com.example.coupon.issue;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@Profile("optimistic")
public class OptimisticLockCouponIssuer implements CouponIssuer {

	private static final int MAX_ATTEMPTS = 100;

	private final OptimisticLockTransaction transaction;
	private final AtomicLong retryCount = new AtomicLong();

	public OptimisticLockCouponIssuer(OptimisticLockTransaction transaction) {
		this.transaction = transaction;
	}

	// 재시도 루프가 트랜잭션 밖에 있어야 한다. 롤백 표시된 트랜잭션은 재사용할 수 없고,
	// 같은 빈의 메서드를 호출하면 프록시를 타지 않아 새 트랜잭션이 열리지 않는다.
	// 백오프를 넣지 않은 것은 의도적이다 - 재시도가 몇 번 필요한지 관찰하는 것이 목적이지
	// 대기 시간을 튜닝하는 것이 목적이 아니다.
	@Override
	public boolean issue(Long couponId, Long userId) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return transaction.attempt(couponId, userId);
			} catch (ObjectOptimisticLockingFailureException e) {
				retryCount.incrementAndGet();
			}
		}
		return false;
	}

	public long getRetryCount() {
		return retryCount.get();
	}
}
