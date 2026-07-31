# 진행 상황

현재 단계: **Phase 3 — Redis 원자 연산**

Phase 완료 조건을 만족하지 못하면 다음으로 넘어가지 않는다.

## 프로젝트 완료 조건

아래 둘을 만족하면 이 프로젝트는 끝난다.

1. `docs/benchmark.md`에 **네 방식(나이브 / DB 락 / Redis / Kafka)의 오버셀 건수와 TPS가
   모두 기록**되어 있을 것
2. `README.md`에 **"어떤 상황에 어떤 방식을 쓸지"가 표로 정리**되어 있을 것

즉 Phase 4까지가 필수 경로이고 README가 마무리다.
Phase 5(MSA 분리)는 이 조건에 들어 있지 않으므로 **선택 과제**로 둔다.
Phase 4까지의 수치가 이 프로젝트의 알맹이고, 서비스를 쪼갤 근거는 그 수치를 보고
다시 판단하는 편이 낫다.

| 방식 | Phase | 오버셀 | TPS |
|---|---|---|---|
| 나이브 | 1 | +7건 | 939 |
| DB 락 (비관적) | 2 | 0건 | 218 |
| DB 락 (낙관적) | 2 | 0건 | 114 |
| Redis | 3 | 미측정 | 미측정 |
| Kafka | 4 | 미측정 | 미측정 |

이 표는 `docs/benchmark.md`의 요약이다. 원본과 어긋나면 `benchmark.md`가 맞다.

## Phase 0 — 프로젝트 셋업

- [x] Gradle 프로젝트 뼈대 (Spring Boot 3.5.16, Gradle 8.14.3, Java 21 toolchain)
- [x] docker-compose (MySQL 8.4 기본, Redis/Kafka는 profile)
- [x] 엔티티 2개 + Flyway V1 마이그레이션
- [x] Testcontainers 스키마 정합성 테스트
- [x] Actuator 헬스체크 + 테스트
- [x] 전체 검증 절차 통과

`start.spring.io`가 더 이상 Spring Boot 3.x를 제공하지 않아 wrapper와 레이아웃만
받아쓰고 `build.gradle`은 직접 작성했다. Boot 4는 스타터 이름이 전부 바뀌어
(`starter-webmvc`, `starter-flyway`, `testcontainers-junit-jupiter`) 그대로 쓸 수 없다.

## Phase 1 — 나이브 구현 + 실패 재현

- [x] `count()` 기반 무락 발급 로직
- [x] 1000 스레드 동시성 테스트에서 오버셀 재현 (`ExecutorService` + `CountDownLatch`)
- [x] 실제 발급 건수를 `docs/benchmark.md`에 기록 (풀 10 → 107건, 풀 50 → 126건)
- [x] k6 부하 테스트로 TPS, p95, p99 측정 (939 / 268ms / 452ms)

완료 조건: 오버셀이 실제로 발생하고 그 수치가 문서에 남아 있을 것. 충족.

**`CouponIssueConcurrencyTest`와 `CouponIssueConcurrencyLargePoolTest`는
실패하는 것이 정상이다.** Phase 1의 산출물이 통과하는 테스트가 아니라
실패한 수치이기 때문이다. 락을 건 구현은 Phase 2에서 통과한다.

## Phase 2 — DB 락으로 해결 (비교군)

- [x] 비관적 락 (`SELECT ... FOR UPDATE`) — TPS 218, 오버셀 0건
- [x] 낙관적 락 (`@Version` + 재시도) — TPS 114, 오버셀 0건, 재시도 808회
- [x] DB unique 제약 (`coupon_id` + `user_id`) — 중복 발급 109건 → 1건
- [x] 세 방식 각각 동시성 테스트 + k6 측정
- [x] gap lock 데드락 관찰 (`docs/known-issues.md`)
- [x] 트레이드오프 표 (`docs/benchmark.md`)

완료 조건: 오버셀 0건, TPS는 Phase 1보다 떨어져 있을 것. **충족.**
나이브 819 → 비관적 218(-73%) → 낙관적 114(-86%).

`innodb_lock_wait_timeout`은 기본값 50초를 유지했다. 커넥션 풀이 10이라
대기 큐가 짧아 조정할 필요가 없었다. 락 대기 타임아웃은 한 번도 발생하지 않았다.

**`NaiveStockRaceTest`와 `NaiveStockRaceLargePoolTest`는 계속 실패한다.**
Phase 1의 오버셀 재현이 목적인 테스트라 의도된 상태다.
`gradlew test`는 실패 2건 / 통과 12건이다.

## Phase 3 — Redis 원자 연산

- [ ] Lua 스크립트로 재고 차감 + 중복 발급 방지를 단일 원자 연산으로
- [ ] `INCR` 후 초과분 롤백 방식과의 차이 및 선택 이유 문서화
- [ ] Redis 성공 직후 앱이 죽는 경우의 정합성 취약점을 `docs/known-issues.md`에 기록
- [ ] Redisson 분산락과 성능 비교 (선택)

완료 조건: 오버셀 0건 + Phase 2 대비 TPS 개선 수치.

## Phase 4 — Kafka 비동기화

- [ ] 토픽/파티션 키 설계와 순서 보장 필요성 판단
- [ ] 멱등키 설계 (DB unique 제약 병행)
- [ ] 컨슈머 중복 수신 시 발급 1건인지 검증
- [ ] DLQ 동작 검증
- [ ] Redis 재고는 깎였는데 DB 저장이 실패한 경우의 보상
- [ ] ID 생성 전략 전환 여부를 측정 후 판단 (IDENTITY가 배치 삽입을 막는다)
- [ ] 컨슈머 lag 확인 방법을 README에 기록

완료 조건: p99 개선 수치 + 컨슈머 lag이 유입량을 따라잡는지 확인 + DLQ 검증.

## Phase 5 — MSA 분리

- [ ] 서비스 경계 후보 2~3개 제시 및 근거 (배포 단위, 부하 특성, 데이터 소유권)
- [ ] `coupon-service`, `notification-service` 분리 (각자 DB 소유)
- [ ] 이벤트 스키마 계약 모듈 (Avro/JSON Schema 중 택1 + 선택 이유)
- [ ] Micrometer Tracing + Zipkin 분산 추적
- [ ] 서비스 하나를 내렸을 때의 동작을 `docs/resilience.md`에 기록
