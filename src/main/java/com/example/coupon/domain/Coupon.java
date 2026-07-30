package com.example.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private int totalQuantity;

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
}
