# 알려진 문제와 관찰 기록

해결한 것이 아니라 **알고 있는 것**을 적는다.
재현 조건과 실제 로그를 남겨 나중에 같은 자리에서 다시 헤매지 않게 한다.

---

## Redis 성공과 DB 저장 사이에 앱이 죽으면 (Phase 3)

**상태:** 미해결. 인지하고 있다. Phase 4의 보상 전략에서 다시 다룬다.

### 무엇이 문제인가

발급은 두 단계다.

```
1) Lua 스크립트   재고 카운터 증가 + 발급자 Set에 추가   (Redis)
2) insert         coupon_issue에 행 추가                (MySQL)
```

**1과 2 사이에 앱이 죽으면** Redis는 발급됐다고 기록하는데 DB에는 행이 없다.

Redis와 MySQL은 서로 다른 저장소이고, **둘에 걸친 원자적 쓰기는 존재하지 않는다.**
어느 쪽을 먼저 쓰든 그 사이에 죽을 수 있다.

### 어떤 상태가 되는가

**과소 판매(under-sell).** 100장 중 1장이 영원히 안 나간다.
카운터는 그 자리를 이미 소진된 것으로 센다.

**더 나쁜 건 그 유저다.** 재시도해도 발급자 Set에 이미 있어서 "중복"으로 거절된다.
**재고는 자기 몫으로 까였는데 쿠폰은 못 받은 상태로 갇힌다.**

오버셀보다는 낫다. 재고를 초과 발급하는 것보다 덜 파는 쪽이 사업적으로 안전하다.
하지만 결함이 사라진 게 아니라 **성격이 바뀐 것**이다.

정상 동작할 때는 어긋나지 않는다. 부하 테스트 직후 확인한 값은
Redis 카운터 27,420 / Redis Set 27,420 / `coupon_issue` 행 27,420으로 모두 같았다.

### 왜 지금 고치지 않는가

Phase 4에서 Kafka를 넣으면 **이 창이 오히려 넓어진다.**
Redis 성공 → Kafka produce 실패, produce 성공 → 컨슈머 DB 저장 실패까지
실패 지점이 늘어난다. 지금 임시 대응을 넣으면 Phase 4에서 다시 걷어내야 한다.

### 알려진 해결 계열 (어느 것도 공짜가 아니다)

| 전략 | 방식 | 대가 |
|---|---|---|
| 보상 트랜잭션 | DB 실패 시 Redis를 되돌린다 | **앱이 죽으면 되돌릴 주체가 없다.** 이 시나리오를 못 막는다 |
| Outbox 패턴 | DB 트랜잭션 안에 이벤트를 같이 저장하고 별도 프로세스가 발행 | 재고 판정이 DB로 돌아와 Redis를 쓰는 의미가 줄어든다 |
| 정합성 대조 배치 | Redis 카운터와 DB 행 수를 주기적으로 비교해 보정 | 창이 배치 주기만큼 열려 있다. 가장 현실적 |

---

## Redis 키에 TTL이 없어 무한히 쌓인다 (Phase 3)

**상태:** 미해결. 의도적으로 미뤘다.

쿠폰마다 키가 두 개 생기고 지워지지 않는다.

```
{coupon:<id>}:issued    발급 수 카운터
{coupon:<id>}:users     발급받은 유저 Set
```

Set이 특히 문제다. 512개를 넘으면 인코딩이 intset에서 hashtable로 바뀌고
**항목당 60~90바이트**가 된다. 100만 명이면 80~90MB이고 쿠폰 수만큼 곱해진다.

**TTL을 걸지 않은 이유는 걸 근거가 없어서다.**
"언제까지 유효한가"를 알아야 TTL을 정하는데 `Coupon`에 이벤트 종료 시각이 없다.
도메인에 없는 개념을 TTL을 위해 만들어 넣는 것은 순서가 거꾸로다.

쿠폰에 유효 기간이 생기면 그때 TTL을 건다.

**규모가 더 커지면** Bitmap(`SETBIT`)이 대안이다. 유저 1억 명이 12.5MB로
압도적으로 작다. 다만 userId가 조밀한 정수라는 가정이 필요하고, 지금 그 근거가 없다.

---

## Redis가 죽으면 발급이 멈춘다 (Phase 3)

**상태:** 의도한 동작이다.

`RedisConnectionFailureException`을 잡지 않는다. 그대로 500이 나간다.

**잡아서 DB 경로로 폴백하면 오버셀이 다시 열린다.**
Phase 1에서 재현한 그 문제로 되돌아가는 셈이다.
가용성보다 정합성을 택한 것이고, 선착순 쿠폰에서는 이쪽이 맞다고 판단했다.

폴백이 필요하다면 DB 경로에도 락이 걸려 있어야 하는데,
그러면 Redis를 쓰는 의미가 절반 사라진다.

---

## 세컨더리 인덱스 범위 락이 만드는 데드락 (Phase 2)

**상태:** 재현 완료. 이 프로젝트에서는 회피했다(쿠폰 행을 PK로 잠근다).

### 무엇이 문제인가

"발급 이력을 잠그고 세자"는 자연스러운 발상이다.

```java
select ci from CouponIssue ci where ci.couponId = :couponId
// @Lock(LockModeType.PESSIMISTIC_WRITE)
```

`coupon_id`는 세컨더리 인덱스라 이건 **범위 락**이 된다.
InnoDB는 기본 격리 수준 REPEATABLE READ에서 인덱스 레코드뿐 아니라
**레코드 사이의 간격(gap)** 까지 잠근다.

여기서 두 가지 성질이 겹쳐 데드락이 된다.

1. **gap lock끼리는 서로 호환된다.** 여러 트랜잭션이 같은 gap을 동시에 잠글 수 있다.
2. **insert-intention lock은 남의 gap lock과 충돌한다.** insert하려면 이 락이 필요하다.

그래서 N개 트랜잭션이 전부 gap lock을 얻은 뒤, 전부 insert를 시도하면
각자가 나머지 전부를 기다리게 된다.

### 재현 조건

| 항목 | 값 |
|---|---|
| 테스트 | `GapLockObservationTest` |
| 스레드 수 | 8 |
| 테이블 상태 | `coupon_issue` 비어 있음 |
| 격리 수준 | REPEATABLE READ (MySQL 8.4 기본값) |
| 동기화 | 모든 트랜잭션이 gap lock을 잡은 뒤 insert하도록 `CountDownLatch`로 맞춤 |

`CountDownLatch`는 재현을 안정화하려고 넣었다.
없어도 같은 문제가 나지만 실행마다 재현 여부가 달라진다.

### 결과

**8개 중 1개 성공, 7개가 `Deadlock found when trying to get lock`.**

`SHOW ENGINE INNODB STATUS`의 `LATEST DETECTED DEADLOCK`에서 핵심 부분:

```
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index uk_coupon_issue_coupon_user of table `test`.`coupon_issue`
trx id 2148 lock_mode X
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index uk_coupon_issue_coupon_user of table `test`.`coupon_issue`
trx id 2148 lock_mode X insert intention waiting
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

읽는 법.

- **`supremum`** — 인덱스의 마지막 레코드 뒤를 가리키는 의사 레코드다.
  테이블이 비어 있으므로 인덱스 전 범위가 하나의 gap이고, 그 gap이 `supremum`으로 표현된다.
- **`lock_mode X`를 HOLDS** — 두 트랜잭션이 **같은 자리를 동시에 잠그고 있다.**
  gap lock끼리 호환되기 때문에 가능한 상태다.
- **`lock_mode X insert intention waiting`** — 그리고 둘 다 같은 자리에 insert하려 한다.
  insert-intention은 상대의 gap lock과 충돌하므로 서로를 기다린다.
- **`WE ROLL BACK TRANSACTION (2)`** — InnoDB가 데드락을 감지하고 한쪽을 죽인다.

### 덤으로 확인한 것

잠긴 인덱스가 `idx_coupon_issue_coupon_id`가 아니라
**`uk_coupon_issue_coupon_user`** 다.
`(coupon_id, user_id)` unique 인덱스를 추가한 뒤, 옵티마이저가
범위 락에는 이쪽을 골랐다.

같은 쿼리라도 인덱스가 바뀌면 잠기는 범위가 바뀐다.
**락 문제를 볼 때는 실행 계획을 같이 봐야 한다.**

참고로 `count(*)` 쿼리의 실행 계획은 바뀌지 않았다.
`EXPLAIN` 결과 `possible_keys`에는 둘 다 올라오지만
`key`는 더 좁은 `idx_coupon_issue_coupon_id`가 선택된다.

### 어떻게 피했는가

Phase 2의 비관적 락은 `coupon_issue`가 아니라 **`coupon` 행을 PK로 잠근다.**

```sql
select * from coupon where id = ? for update
```

PK 단일 행 락에는 gap이 없다. 데드락도 나지 않는다.
`PessimisticLockStockRaceTest`는 1000 스레드에서 예외 0건으로 통과한다.

**교훈: 무엇을 잠글지 고르는 것이 어떻게 잠글지 고르는 것보다 중요하다.**

---

## 부하 테스트 중 연결 단계 실패 (Phase 1)

**상태:** 원인 파악 완료. 조건을 바꾸지 않고 그대로 둔다.

k6로 VU 200을 걸면 `http_req_failed`가 0.1% 안팎 발생한다.

```
dial tcp 127.0.0.1:8080: connectex:
No connection could be made because the target machine actively refused it.
```

**애플리케이션 오류가 아니다.** 근거 세 가지.

1. 앱 로그에 예외 0건
2. DB 행 수가 `coupon_issued` 카운터와 정확히 일치
3. `발급 + 실패 = 총 요청 수`로 맞아떨어짐

200 VU가 동시에 연결을 열 때 Tomcat의 accept 백로그가 넘쳐 RST가 나간 것이고,
관측된 경고는 모두 테스트 시작 직후에 찍혔다.

램프업을 넣거나 `server.tomcat.accept-count`를 올리면 없앨 수 있지만 그대로 둔다.
선착순 쿠폰은 원래 모두가 동시에 몰리는 시나리오이고,
모든 Phase를 같은 조건으로 측정하므로 비교에는 영향이 없다.
