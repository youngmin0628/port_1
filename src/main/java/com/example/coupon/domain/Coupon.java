package com.example.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private int totalQuantity;

	@Column(nullable = false)
	private int issuedQuantity;

	@Version
	private long version;

	protected Coupon() {
	}

	public Coupon(String name, int totalQuantity) {
		this.name = name;
		this.totalQuantity = totalQuantity;
	}

	public Long getId() {
		return id;
	}

	public int getTotalQuantity() {
		return totalQuantity;
	}

	// 낙관적 락 구현만 쓴다. 나머지 구현은 여전히 coupon_issue 행 개수로 재고를 판단한다.
	// 낙관적 락은 버전을 걸 상태가 행 안에 있어야 성립하는데, 발급 수가 다른 테이블의
	// 행 개수로만 표현되면 Coupon 행에는 잠글 것이 없다.
	public boolean tryIssue() {
		if (issuedQuantity >= totalQuantity) {
			return false;
		}
		issuedQuantity++;
		return true;
	}

	public int getIssuedQuantity() {
		return issuedQuantity;
	}
}
