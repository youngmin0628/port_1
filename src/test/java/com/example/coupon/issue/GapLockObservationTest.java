package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.ContainerTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

// "발급 이력을 잠그고 세자"는 꽤 자연스러운 발상이지만, coupon_id로 범위 락을 걸면
// InnoDB가 인덱스 레코드 사이의 간격까지 잠근다. gap lock끼리는 서로 호환되므로 여러
// 트랜잭션이 동시에 잠글 수 있는데, 그 다음 insert가 필요로 하는 insert-intention lock은
// 남의 gap lock과 충돌한다. 서로가 서로를 기다리는 데드락이 된다.
//
// 쿠폰 행을 PK로 잠그는 비관적 락에는 이 문제가 없다. 단일 행 락이라 gap이 없다.
@SpringBootTest
@ActiveProfiles("naive")
class GapLockObservationTest extends ContainerTestBase {

	private static final int THREAD_COUNT = 8;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	// 통과/실패를 단정하지 않는다. 관찰이 목적이므로 결과를 출력하고 항상 통과시킨다.
	@Test
	void 세컨더리_인덱스_범위_락에서_무슨_일이_생기는지_관찰한다() throws Exception {
		Coupon coupon = couponRepository.save(new Coupon("gap lock 관찰", 1000));
		List<String> failures = new CopyOnWriteArrayList<>();

		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
		CountDownLatch done = new CountDownLatch(THREAD_COUNT);

		for (int i = 0; i < THREAD_COUNT; i++) {
			long userId = i;
			executor.submit(() -> {
				try {
					transactionTemplate.execute(status -> {
						couponIssueRepository.findByCouponIdForUpdate(coupon.getId());

						// 모든 트랜잭션이 gap lock을 잡은 뒤에 insert를 시도하게 맞춘다.
						// 이 대기가 없어도 같은 문제가 나지만 실행마다 재현 여부가 달라진다.
						ready.countDown();
						대기한다(ready);

						couponIssueRepository.save(
								new CouponIssue(coupon.getId(), userId, LocalDateTime.now()));
						return null;
					});
				} catch (Exception e) {
					failures.add(e.getClass().getSimpleName() + " / " + 첫줄(근본원인(e)));
				} finally {
					done.countDown();
				}
			});
		}

		done.await();
		executor.shutdown();

		System.out.printf("[GapLock] 스레드=%d, 성공=%d, 실패=%d%n",
				THREAD_COUNT, THREAD_COUNT - failures.size(), failures.size());
		failures.forEach(f -> System.out.println("[GapLock] " + f));

		System.out.println("[GapLock-STATUS-START]");
		System.out.println(데드락_섹션());
		System.out.println("[GapLock-STATUS-END]");
	}

	private void 대기한다(CountDownLatch ready) {
		try {
			ready.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// 애플리케이션 계정에는 PROCESS 권한이 없어 show engine innodb status를 실행할 수 없다.
	// 관찰용이므로 컨테이너 안에서 root로 직접 부른다.
	private String 데드락_섹션() throws Exception {
		String text = MYSQL
				.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(),
						"-e", "show engine innodb status\\G")
				.getStdout();

		int start = text.indexOf("LATEST DETECTED DEADLOCK");
		if (start < 0) {
			return "LATEST DETECTED DEADLOCK 섹션이 없다 - 데드락이 기록되지 않았다";
		}
		return text.substring(start, Math.min(text.length(), start + 2500));
	}

	private Throwable 근본원인(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}

	private String 첫줄(Throwable e) {
		String message = e.getMessage();
		if (message == null) {
			return e.getClass().getName();
		}
		int end = message.indexOf('\n');
		return end < 0 ? message : message.substring(0, end);
	}
}
