package com.example.coupon.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	long countByCouponId(Long couponId);

	// gap lock 관찰 실험 전용. coupon_id는 세컨더리 인덱스라 범위 락이 걸리고,
	// InnoDB는 REPEATABLE READ에서 레코드뿐 아니라 레코드 사이의 간격까지 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select ci from CouponIssue ci where ci.couponId = :couponId")
	List<CouponIssue> findByCouponIdForUpdate(@Param("couponId") Long couponId);
}
