package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("optimistic")
class OptimisticLockStockRaceTest extends StockRaceTestBase {

	@Autowired
	private OptimisticLockCouponIssuer issuer;

	// issued_quantity 컬럼이 생기면서 재고의 진실의 원천이 둘(컬럼과 행 개수)이 됐다.
	// 둘이 어긋나면 정합성 구멍이므로 함께 검증한다.
	@Test
	void 발급_수_컬럼과_실제_행_개수가_일치한다() throws InterruptedException {
		// 카운터는 싱글턴 빈에 있어 테스트 메서드에 걸쳐 누적된다. 이번 실행분만 보려면 델타를 재야 한다.
		long before = issuer.getRetryCount();
		long issuedRows = 재고_경합을_실행한다();
		Coupon coupon = couponRepository.findAll().get(0);

		System.out.printf("[OptimisticLock] 컬럼=%d, 행=%d, 재시도=%d%n",
				coupon.getIssuedQuantity(), issuedRows, issuer.getRetryCount() - before);

		assertThat((long) coupon.getIssuedQuantity()).isEqualTo(issuedRows);
	}
}
