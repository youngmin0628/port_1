package com.example.coupon.issue;

public interface CouponIssuer {

	boolean issue(Long couponId, Long userId);
}
