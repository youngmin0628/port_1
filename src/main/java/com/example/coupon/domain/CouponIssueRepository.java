package com.example.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	long countByCouponId(Long couponId);
}
