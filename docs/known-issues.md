# 알려진 문제와 관찰 기록

해결한 것이 아니라 **알고 있는 것**을 적는다.
재현 조건과 실제 로그를 남겨 나중에 같은 자리에서 다시 헤매지 않게 한다.

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
