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

abstract class DuplicateIssueTestBase extends ContainerTestBase {

	private static final int TOTAL_QUANTITY = 100;
	private static final int THREAD_COUNT = 1000;
	private static final long SAME_USER = 1L;

	@Autowired
	private CouponIssuer couponIssuer;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	// 예외 수가 이 테스트의 두 번째 관찰 대상이다. 정합성은 어느 방식이든 확보되지만
	// 요청이 DB까지 가느냐 아니냐가 갈린다.
	protected final AtomicInteger exceptions = new AtomicInteger();

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
		exceptions.set(0);
	}

	@Test
	void 같은_유저가_1000번_요청해도_1장만_발급된다() throws InterruptedException {
		assertThat(중복_요청을_실행한다()).isEqualTo(1);
	}

	protected long 중복_요청을_실행한다() throws InterruptedException {
		Coupon coupon = couponRepository.save(new Coupon("선착순 100장", TOTAL_QUANTITY));

		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

		for (int i = 0; i < THREAD_COUNT; i++) {
			executor.submit(() -> {
				try {
					couponIssuer.issue(coupon.getId(), SAME_USER);
				} catch (Exception e) {
					exceptions.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await();
		executor.shutdown();

		long issued = couponIssueRepository.countByCouponId(coupon.getId());
		System.out.printf("[%s] 발급=%d, 예외=%d%n",
				getClass().getSimpleName(), issued, exceptions.get());

		return issued;
	}
}
