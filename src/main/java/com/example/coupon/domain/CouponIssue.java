package com.example.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Coupon 연관관계를 걸지 않는다. lazy 프록시 초기화 쿼리가 Phase별 TPS 비교에 노이즈를 만든다.
	@Column(nullable = false)
	private Long couponId;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private LocalDateTime issuedAt;

	protected CouponIssue() {
	}

	// issuedAt을 인자로 받는다. 엔티티가 직접 now()를 부르면 시간을 통제할 수 없다.
	public CouponIssue(Long couponId, Long userId, LocalDateTime issuedAt) {
		this.couponId = couponId;
		this.userId = userId;
		this.issuedAt = issuedAt;
	}
}
