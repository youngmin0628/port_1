package com.example.coupon.issue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 중복은 DB unique 제약이 막는다. 정합성은 확보되지만 모든 요청이 DB까지 가서
// DataIntegrityViolationException으로 튕긴다.
@SpringBootTest
@ActiveProfiles("naive")
class NaiveDuplicateIssueTest extends DuplicateIssueTestBase {
}
