package com.example.coupon.issue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("redis")
class RedisStockRaceTest extends StockRaceTestBase {
}
