package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.ContainerTestBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

abstract class StockRaceTestBase extends ContainerTestBase {

	protected static final int TOTAL_QUANTITY = 100;
	private static final int THREAD_COUNT = 1000;

	@Autowired
	private CouponIssuer couponIssuer;

	@Autowired
	protected CouponRepository couponRepository;

	@Autowired
	protected CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 동시에_1000명이_요청해도_100장만_발급된다() throws InterruptedException {
		assertThat(재고_경합을_실행한다()).isEqualTo(TOTAL_QUANTITY);
	}

	protected long 재고_경합을_실행한다() throws InterruptedException {
		Coupon coupon = couponRepository.save(new Coupon("선착순 100장", TOTAL_QUANTITY));
		AtomicInteger exceptions = new AtomicInteger();

		// 스레드 풀을 작게 잡으면 스레드 풀이 병목이 되어 DB 경합을 관찰할 수 없다.
		// 실제 상한은 HikariCP 풀 크기이고, 그게 이 테스트의 관찰 대상이다.
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

		for (int i = 0; i < THREAD_COUNT; i++) {
			long userId = i;
			executor.submit(() -> {
				try {
					couponIssuer.issue(coupon.getId(), userId);
				} catch (Exception e) {
					// 예외를 세지 않고 삼키면 DB 오류를 오버셀로 착각한다.
					exceptions.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await();
		executor.shutdown();

		long issued = couponIssueRepository.countByCouponId(coupon.getId());
		System.out.printf("[%s] 발급=%d, 초과=%d, 예외=%d%n",
				getClass().getSimpleName(), issued, issued - TOTAL_QUANTITY, exceptions.get());

		return issued;
	}
}
