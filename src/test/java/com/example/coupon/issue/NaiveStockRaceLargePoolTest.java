package com.example.coupon.issue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 오버셀 규모의 상한은 동시에 열릴 수 있는 트랜잭션 수, 즉 커넥션 풀 크기가 정한다.
// 기본 풀(10)과 비교해 그 관계를 수치로 남기려고 만든 클래스다.
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
@ActiveProfiles("naive")
class NaiveStockRaceLargePoolTest extends StockRaceTestBase {
}
