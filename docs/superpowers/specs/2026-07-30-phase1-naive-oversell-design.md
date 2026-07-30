# Phase 1 — 나이브 구현 + 오버셀 재현 설계

작성일: 2026-07-30
상태: 승인됨

## 1. 목표

**"잘 동작하는 구현"이 아니라 "동시성 문제를 재현하는 것"이 목표다.**

Phase 1의 산출물은 통과하는 테스트가 아니라 **실패하는 테스트와 그 수치**다.
락 없는 read-modify-write가 왜 깨지는지, 얼마나 깨지는지를 숫자로 남긴다.
이 수치가 Phase 2~4의 모든 개선을 평가하는 기준선이 된다.

의도적으로 취약한 코드를 쓴다. 리뷰에서 "락을 걸어야 한다"는 지적이 나오면 그게 정답이고,
Phase 2에서 그렇게 한다. 지금 고치면 Phase 2의 의미가 사라진다.

## 2. 범위

### 하는 것

- `CouponRepository`, `CouponIssueRepository` (Spring Data JPA)
- `CouponIssueService.issue(couponId, userId)` — `count()` → 비교 → `save()`. 락 없음
- `CouponIssueController` — `POST /coupons/{couponId}/issues`
- 엔티티에 필요한 만큼의 생성자/getter 추가 (Phase 0에서 의도적으로 비워뒀다)
- 동시성 테스트 — 1000 스레드, `ExecutorService` + `CountDownLatch`, 오버셀 재현
- 커넥션 풀 크기와 오버셀 규모의 관계 측정 (풀 10 / 풀 50)
- k6 부하 테스트 스크립트 + TPS / p95 / p99 측정
- `docs/benchmark.md` 갱신 (고정 조건 수정 + 첫 결과 행)
- `docs/progress.md` 갱신

### 하지 않는 것 (그리고 이유)

| 항목 | 왜 안 하는가 |
|---|---|
| 어떤 형태의 락도 | Phase 2의 작업. 나이브 구현의 실패를 먼저 재현해야 한다 |
| Redis, Kafka | Phase 3 / Phase 4 |
| 중복 발급 방지 (같은 유저 2회) | Phase 2(c) unique 제약과 Phase 3의 재료다. Phase 1은 **재고 경합만** 재현한다 |
| 전역 예외 핸들러 / `@RestControllerAdvice` | 6.3절 참조. 결과를 boolean으로 돌려 컨트롤러에서 상태코드로 매핑한다 |
| 요청 DTO 검증 (`@Valid`, `@NotNull`) | 잘못된 입력은 Phase 1의 관심사가 아니다 |
| `CouponIssueStrategy` 류 인터페이스 | 구현체가 2개 이상 필요해지는 Phase 2에서 추출한다 |
| 재시도 로직 | Phase 2 낙관적 락에서 처음 필요해진다 |

## 3. Phase 0에서 발견한 결함 — 벤치마크 고정 조건 수정

`docs/benchmark.md`에 커밋한 고정 조건은 **쿠폰 수량 100 + k6 VU 200 / 30초**였다.
이대로 측정하면 안 된다.

수량이 100이면 첫 수십 밀리초에 전량이 소진되고, **남은 29.9초는 전부 품절 응답**이다.
그러면 TPS와 p95/p99가 발급 경로가 아니라 거절 경로의 비용을 재게 된다.
Phase 1↔2↔3 비교가 무의미해지고, "왜 Redis를 쓰는가"에 대한 근거가 사라진다.

**조건을 목적에 따라 둘로 쪼갠다.**

| | 정합성 테스트 (JUnit + Testcontainers) | 부하 테스트 (k6) |
|---|---|---|
| 쿠폰 총 수량 | 100 | 1,000,000 |
| 부하 | 1000 스레드 | VU 200, 30s |
| 측정 대상 | 최종 발급 건수 (= 오버셀 규모) | TPS, p95, p99 |
| 통과 기준 | Phase 1은 실패, Phase 2 이후는 100건 정확히 | Phase 간 상대 비교 |

부하 테스트의 수량 1,000,000은 30초 안에 소진되지 않도록 넉넉히 잡은 값이다.
모든 요청이 실제 발급 경로를 타야 Phase 간 비교가 성립한다.

**부하 테스트 전 DB를 초기화한다.** Phase 1의 `count()` 비용은 누적 행 수에 비례하므로,
이전 실행이 남긴 행이 있으면 측정값이 달라진다.

## 4. 오버셀 규모는 커넥션 풀 크기가 결정한다

이건 측정 전에 예상해두고 결과와 맞춰볼 항목이다.

`count()`와 `save()` 사이의 창에서 다른 트랜잭션이 끼어들어야 오버셀이 생긴다.
그런데 동시에 실행될 수 있는 트랜잭션 수는 HikariCP 풀 크기(10)가 상한이다.
1000 스레드를 던져도 DB에서 실제로 겹치는 건 최대 10개다.

따라서 **경계에서 겹치는 양도 대략 10 수준이고, 최종 발급 건수는 110건 근처로 예상한다.**
`PROMPTS.md`가 예로 든 137건 같은 큰 수는 풀 10에서는 나오지 않는다.

이걸 한계로 남기지 않고 관계로 만든다. **풀 크기 10과 50으로 각각 측정해
오버셀 규모가 어떻게 변하는지 표로 남긴다.** 큰 숫자 하나보다 이 관계가 분석으로 읽힌다.

예측이 틀리면 그것도 그대로 기록한다. 예측과 실측이 다른 이유를 찾는 것이 이 프로젝트다.

## 5. 아키텍처

```
POST /coupons/{couponId}/issues
        │
        ▼
CouponIssueController          얇다. 요청 본문에서 userId를 꺼내고 상태코드로 매핑만 한다
        │
        ▼
CouponIssueService.issue()     @Transactional
        │                      1) couponRepository.findById()      → 쿠폰 존재 확인
        │                      2) couponIssueRepository.countByCouponId()
        │                      3) issued >= totalQuantity 이면 false 반환
        │                      4) couponIssueRepository.save()
        │                      5) true 반환
        ▼
MySQL                          락 없음. 3번과 4번 사이가 취약 구간이다
```

취약 구간은 2번의 `count()`와 4번의 `save()` 커밋 사이다.
MySQL 기본 격리 수준 REPEATABLE READ에서 각 트랜잭션은 첫 읽기 시점의 스냅샷을 본다.
트랜잭션 A와 B가 거의 동시에 시작하면 둘 다 `count()`에서 99를 보고,
둘 다 조건을 통과하고, 둘 다 insert한다. 결과는 101이다.

## 6. 상세 설계

### 6.1 엔티티 보강

Phase 0에서는 필드와 protected 기본 생성자만 만들었다.
이제 엔티티를 만들고 읽는 코드가 생겼으므로 필요한 만큼만 추가한다.

**`Coupon`** — `Coupon(String name, int totalQuantity)` 생성자, `getId()`, `getTotalQuantity()`.
`getName()`은 읽는 곳이 없으므로 만들지 않는다.

**`CouponIssue`** — `CouponIssue(Long couponId, Long userId, LocalDateTime issuedAt)` 생성자.
getter는 읽는 곳이 없으므로 만들지 않는다.

`issuedAt`을 생성자 인자로 받는다. 엔티티 안에서 `LocalDateTime.now()`를 부르면
테스트에서 시간을 통제할 수 없다. Phase 1에 시간 검증은 없지만 이 습관은 지킨다.

### 6.2 리포지토리

**`CouponRepository extends JpaRepository<Coupon, Long>`** — 추가 메서드 없음.

**`CouponIssueRepository extends JpaRepository<CouponIssue, Long>`**

```java
long countByCouponId(Long couponId);
```

`PROMPTS.md`가 명시한 나이브 방식이다. `idx_coupon_issue_coupon_id`를 타므로
느린 쿼리가 아니라 동시성이 측정 대상이 된다.

Phase 1에서 이 메서드 하나만 추가한다. `deleteAll` 등은 `JpaRepository`가 준다.

### 6.3 서비스

```java
@Service
public class CouponIssueService {

    @Transactional
    public boolean issue(Long couponId, Long userId) { ... }
}
```

**반환 타입을 boolean으로 둔다.** 예외를 던지고 `@RestControllerAdvice`로 받는 편이
Spring 관례에는 가깝지만, Phase 1에 예외 체계를 세우는 것은 요청되지 않은 구조다.
품절은 예외 상황이 아니라 정상적인 결과 중 하나다.
Phase 2에서 낙관적 락 재시도가 들어오면 결과 타입을 다시 검토한다.

쿠폰이 없으면 어떻게 하는가 — `findById().orElseThrow()`로 두고 500이 나가게 둔다.
k6와 테스트는 항상 존재하는 쿠폰 ID를 쓰므로 이 경로는 측정에 등장하지 않는다.
없는 쿠폰에 대한 응답 설계는 Phase 1의 관심사가 아니다.

**의도적으로 취약한 코드임을 주석 한 줄로 남긴다.** 이 프로젝트에서 주석이 정당한
경우 중 첫 번째다(스펙 4.1절).

### 6.4 컨트롤러

```
POST /coupons/{couponId}/issues
요청 본문: {"userId": 1}
```

| 결과 | 상태코드 |
|---|---|
| 발급 성공 | 200 |
| 품절 | 409 Conflict |

k6가 성공과 품절을 상태코드로 구분해야 한다.
둘 다 200으로 돌리면 부하 테스트에서 실제 발급률을 알 수 없다.

요청 본문은 record 하나로 받는다.

```java
public record CouponIssueRequest(Long userId) {}
```

`DTO` 접미사를 붙이지 않는다. 컨트롤러 파일 안에 중첩하지 않고
같은 패키지에 별도 파일로 둔다 — Phase 4에서 Kafka 메시지 스키마와 나란히 놓고 볼 것이다.

### 6.5 동시성 테스트

`CouponIssueConcurrencyTest` — Testcontainers MySQL 위에서 서비스를 직접 호출한다.
HTTP를 거치면 톰캣 스레드 풀이 또 다른 변수가 된다.

```
쿠폰 준비: totalQuantity = 100
스레드 1000개를 ExecutorService에 던지고 CountDownLatch로 완료를 기다린다
countByCouponId()로 최종 발급 건수를 읽는다
assert 발급 건수 == 100   ← Phase 1에서는 실패한다
```

**설계 결정 세 가지.**

1. **스레드 풀 크기는 1000으로 잡는다.** `newFixedThreadPool(1000)`.
   풀을 작게 잡으면 스레드 풀이 병목이 되어 DB 경합을 관찰할 수 없다.
   실제 상한은 HikariCP 풀(10)이고, 그게 4절의 관찰 대상이다.
2. **작업 안에서 예외를 삼키지 않는다.** `try/finally`로 `countDownLatch.countDown()`만
   보장하고, 예외는 `AtomicInteger`로 세어 테스트 끝에 출력한다.
   삼키면 DB 오류를 오버셀로 착각한다.
3. **실패 메시지에 실제 건수를 담는다.** 이 숫자가 Phase 1의 산출물이다.
   AssertJ의 기본 메시지로 충분하다 — `expected: 100L but was: 112L`.

이 테스트는 **실패하는 상태로 커밋한다.** 통과시키려고 고치지 않는다.
CI가 없으므로 실패하는 테스트가 커밋되어도 다른 작업을 막지 않는다.
`docs/progress.md`에 "Phase 1의 이 테스트는 실패가 정상"임을 적어둔다.

풀 50 측정은 `@DynamicPropertySource`로 `spring.datasource.hikari.maximum-pool-size`를
덮은 별도 테스트 클래스로 만든다. 같은 클래스에서 풀 크기를 바꿀 수 없다 —
데이터소스는 컨텍스트당 하나다.

### 6.6 k6 부하 테스트

`load-test/phase1-issue.js`

```
options: { vus: 200, duration: '30s' }
쿠폰 ID는 환경변수로 받는다 (__ENV.COUPON_ID)
userId는 VU 번호와 반복 횟수로 만들어 매 요청 다르게 한다
thresholds 없음 - 측정이 목적이고 통과/실패 판정이 목적이 아니다
```

성공(200)과 품절(409)을 각각 카운트해서 출력한다.
수량 1,000,000이므로 정상이라면 409는 0건이어야 한다. 409가 나왔다면
30초 안에 100만 건이 나갔다는 뜻이고, 그건 조건을 다시 잡아야 한다는 신호다.

측정 절차:

1. `docker compose down -v && docker compose up -d` — DB 초기화
2. 앱 기동 (`gradlew bootRun`)
3. 수량 1,000,000인 쿠폰을 하나 만든다 (SQL insert 직접)
4. `k6 run -e COUPON_ID=<id> load-test/phase1-issue.js`
5. `http_reqs` rate → TPS, `http_req_duration` p95/p99를 기록

쿠폰 생성 API는 만들지 않는다. 요청되지 않았고, SQL 한 줄로 충분하다.

### 6.7 에러 처리

Phase 1에 에러 처리 설계는 없다. 품절은 에러가 아니라 결과다.
존재하지 않는 쿠폰은 500으로 나가게 두고, 그 경로는 측정에 등장하지 않는다.

동시성 테스트에서 발생한 예외는 세어서 보고한다 —
삼키면 DB 오류와 오버셀을 구분할 수 없다.

## 7. 파일 구조

```
src/main/java/com/example/coupon/
├── CouponApplication.java                      (기존)
├── domain/
│   ├── Coupon.java                             수정 — 생성자, getter 추가
│   ├── CouponIssue.java                        수정 — 생성자 추가
│   ├── CouponRepository.java                   신규
│   └── CouponIssueRepository.java              신규
└── issue/
    ├── CouponIssueService.java                 신규
    ├── CouponIssueController.java              신규
    └── CouponIssueRequest.java                 신규

src/test/java/com/example/coupon/
├── CouponApplicationTests.java                 (기존)
├── issue/
│   ├── CouponIssueConcurrencyTest.java         신규 — 풀 10
│   └── CouponIssueConcurrencyLargePoolTest.java 신규 — 풀 50

load-test/
└── phase1-issue.js                             신규

docs/
├── benchmark.md                                수정 — 고정 조건 분리 + 첫 결과 행
└── progress.md                                 수정
```

패키지를 `domain`과 `issue`로 나눈다. 엔티티·리포지토리는 Phase 2~4에서 계속 공유되고,
발급 흐름은 Phase마다 구현이 바뀐다. 변경 주기가 다른 것을 같이 두지 않는다.

## 8. 검증 절차 (Phase 1 완료 조건)

1. `gradlew test --tests '*CouponIssueConcurrencyTest'` → **실패**하고,
   실패 메시지의 실제 발급 건수가 100을 초과한다
2. 풀 50 테스트도 실패하고, 오버셀 규모가 풀 10보다 크다
3. 두 수치가 `docs/benchmark.md`에 기록된다
4. `CouponApplicationTests` 2개는 여전히 통과한다
5. k6 실행이 완료되고 409가 0건이다 (수량이 소진되지 않았다는 확인)
6. TPS / p95 / p99가 `docs/benchmark.md` 결과 표의 Phase 1 행에 기록된다
7. read-modify-write 관점의 원인 설명이 문서에 남는다
8. `origin/main`에 push 완료

## 9. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 오버셀이 재현되지 않는다 (정확히 100건) | Phase 1의 목적 미달 | 풀 크기를 올려 경합을 키운다. 그래도 안 되면 `count()`와 `save()` 사이의 실제 실행 순서를 로그로 확인한다. 재현 실패 자체도 기록한다 |
| 1000 스레드 생성이 로컬에서 무겁다 | 테스트가 느리거나 OOM | 플랫폼 스레드 1000개는 6C/12T 16GB에서 문제되지 않는다. 실패하면 가상 스레드로 바꾸고 조건에 기록한다 |
| k6 30초 안에 100만 건 소진 | 부하 테스트 조건 무효 | 409 카운트로 감지한다. 발생 시 수량을 올려 재측정 |
| 실패하는 테스트가 커밋된다 | `gradlew test`가 항상 빨간불 | 의도된 상태다. `docs/progress.md`에 명시한다. Phase 2에서 통과로 바뀐다 |
| 풀 50 테스트가 컨텍스트를 새로 만든다 | 테스트 시간 증가 | 컨테이너는 재사용되지만 스프링 컨텍스트는 별개다. 수십 초 추가는 감수한다 |

## 10. 다음 Phase로 넘기는 항목

- **Phase 2**: 락 3종, `@Version` 컬럼과 unique 제약 마이그레이션,
  `innodb_lock_wait_timeout` 조정, gap lock 데드락 관찰,
  `boolean` 반환 타입 재검토, 구현체 2개 이상이 되면 인터페이스 추출
- **Phase 3**: Redis. 이때 `count()` 기반 재고 확인이 사라진다
- **Phase 4**: Kafka. `IDENTITY`가 배치 삽입을 막는 문제를 측정 후 판단
