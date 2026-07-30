# Phase 2 — DB 락으로 해결 (비교군) 설계

작성일: 2026-07-30

## 1. 목표

Phase 1의 오버셀을 DB 락으로 해결하고 **그 대가를 수치로 확인한다.**

이 Phase는 "왜 Redis를 쓰는가"에 대한 유일한 근거다.
정합성을 확보한 뒤 TPS가 Phase 1보다 떨어지는 것을 확인하지 못하면
Phase 3 이후의 모든 작업이 근거를 잃는다.

**TPS가 떨어지지 않으면 부하가 부족한 것이다.** 조건을 다시 잡는다.

## 2. 이 Phase에서 밝혀야 할 것

`PROMPTS.md` 원안은 세 방식(비관적 락 / 낙관적 락 / unique 제약)에
"동일한 동시성 테스트를 통과시킨다"였다. 설계하면서 이게 성립하지 않는다는 걸 확인했다.

**`(coupon_id, user_id)` unique 제약은 재고 초과를 막지 못한다.**
서로 다른 유저 1000명이 각자 한 번씩 요청하면 unique 제약에 걸리는 행이 하나도 없다.
오버셀은 그대로 난다. unique 제약이 막는 것은 **같은 유저의 중복 발급**이다.

따라서 이 Phase의 결과물은 "세 방식 중 뭐가 빠른가"가 아니라
**"각각 무엇을 막는 장치인가"** 다. 목표 매트릭스는 아래와 같다.

| | 재고 경합 (유저 1000명 × 1회) | 중복 발급 (유저 1명 × 1000회) |
|---|---|---|
| 나이브 (Phase 1) | 실패 — 107건 | 실패 — 예상 100건 |
| 비관적 락 | 통과 — 100건 | 실패 — 예상 100건 |
| 낙관적 락 | 통과 — 100건 | 실패 — 예상 100건 |
| unique 제약 단독 | 실패 — 오버셀 그대로 | 통과 — 1건 |
| 비관적 락 + unique | 통과 | 통과 |

**대각선으로 갈린다는 것이 요점이다.** 락은 재고를 지키고 unique 제약은 중복을 막는다.
둘은 대체재가 아니라 보완재이며, 실서비스에는 둘 다 필요하다.

이 표를 실측으로 채우는 것이 Phase 2의 완료 조건이다.
예상과 실측이 다르면 실측을 적고 이유를 찾는다.

## 3. 범위

### 하는 것

- `CouponIssuer` 인터페이스 추출 (구현체가 3개가 되는 시점이다)
- 비관적 락 구현 — `SELECT ... FOR UPDATE`
- 낙관적 락 구현 — `@Version` + 재시도
- `(coupon_id, user_id)` unique 제약 마이그레이션
- 중복 발급 동시성 테스트 신규
- 재고 경합 동시성 테스트를 구현별로 실행
- gap lock / next-key lock 관찰 실험
- k6 측정 3회 (나이브 / 비관적 / 낙관적) — 동일 조건
- 트레이드오프 표 작성
- `docs/benchmark.md`, `docs/progress.md` 갱신

### 하지 않는 것

| 항목 | 왜 안 하는가 |
|---|---|
| Redis, Kafka | Phase 3 / Phase 4 |
| 재고를 `issued_quantity` 컬럼으로 바꿔 비관적 락 최적화 | 4.3절. 그건 락의 효과가 아니라 알고리즘 개선이다 |
| 분산 락 (Redisson 등) | Phase 3의 비교 대상 |
| 재시도 백오프 정책 튜닝 | 재시도가 몇 번 일어나는지 **관찰**하는 것이 목적이지 최적화가 목적이 아니다 |
| 전역 예외 핸들러 | 여전히 만들지 않는다 |

## 4. 결정 사항과 근거

### 4.1 구현 전환은 Spring profile로

구현체가 3개가 되므로 `CouponIssuer` 인터페이스를 추출한다.
Phase 0/1에서 미루기로 했던 추상화이고, 이제 실제로 필요해졌다.

```java
public interface CouponIssuer {
    boolean issue(Long couponId, Long userId);
}
```

구현체는 각각 `@Profile`을 단다. 컨트롤러는 `CouponIssuer` 하나만 주입받는다.

| 프로파일 | 구현체 |
|---|---|
| `naive` (기본값) | `NaiveCouponIssuer` |
| `pessimistic` | `PessimisticLockCouponIssuer` |
| `optimistic` | `OptimisticLockCouponIssuer` |

**API 파라미터나 요청 헤더로 전환하지 않는다.** 측정용 스위치를 프로덕션 API에
노출시키지 않기 위해서다. 프로파일 전환은 앱 재시작을 요구하지만,
어차피 k6 측정 전에는 DB를 비우고 앱을 다시 띄워야 하므로 추가 비용이 없다.

`application.yml`에 `spring.profiles.active: ${ISSUE_STRATEGY:naive}`를 둔다.

### 4.2 비관적 락은 `coupon` 행을 잠근다

```sql
select * from coupon where id = ? for update
```

Spring Data JPA의 `@Lock(LockModeType.PESSIMISTIC_WRITE)`로 건다.
쿠폰 행 하나를 잠그므로 같은 쿠폰에 대한 발급 요청이 완전히 직렬화된다.

PK 단일 행 락이라 **gap lock이 생기지 않고 데드락도 나지 않는다.**
gap lock 관찰은 4.6절의 별도 실험에서 다룬다.

### 4.3 비관적 락에서 `count()`를 그대로 쓴다

락을 잡은 뒤에도 발급 수는 `countByCouponId()`로 센다.
`coupon.issued_quantity` 컬럼을 증가시키는 방식으로 바꾸지 않는다.

**이유: 락의 대가만 분리해서 재기 위해서다.**
알고리즘을 같이 바꾸면 TPS 차이가 락 때문인지 알고리즘 때문인지 구분할 수 없다.
Phase 1과 유일하게 다른 것이 락이어야 비교가 성립한다.

컬럼 방식이 더 빠르다는 것은 안다. 그건 락의 효과가 아니라 알고리즘 개선이고,
Phase 3에서 재고를 Redis 카운터로 옮길 때 같은 성격의 개선이 훨씬 크게 일어난다.

이 선택의 부작용도 유지된다 — 발급 행이 쌓일수록 `count()`가 느려진다.
Phase 1에서 관찰한 그 성질이 비관적 락에서도 그대로 나타나는지 확인한다.

### 4.4 낙관적 락은 도메인 모델을 바꿔야 성립한다

**이게 이 Phase에서 가장 중요한 발견이다.**

낙관적 락은 "내가 읽은 버전이 그대로면 쓴다"인데, 발급 수가 `coupon_issue`의
**행 개수**로만 표현되어 있으면 `Coupon` 행에는 걸 버전이 없다.
`coupon_issue`에 행을 추가해도 `Coupon`의 version은 변하지 않는다.

따라서 낙관적 락을 쓰려면 재고 상태를 `Coupon` 행의 컬럼으로 들고 있어야 한다.

```
coupon.issued_quantity  — 발급 수를 컬럼으로 보유
coupon.version          — @Version
```

즉 **낙관적 락은 도메인 모델의 변경을 요구한다.**
비관적 락은 기존 모델에 락만 얹으면 됐지만 낙관적 락은 그렇지 않다.
이 차이가 트레이드오프 표의 "구현 복잡도" 항목의 실체다.

`issued_quantity`가 생기면 진실의 원천이 둘(컬럼과 행 개수)이 된다.
낙관적 락 구현만 컬럼을 쓰고 나머지는 행 개수를 쓴다.
**두 값이 어긋날 수 있다는 것을 인지하고, 낙관적 락 테스트에서 둘이 일치하는지 함께 검증한다.**

### 4.5 낙관적 락 재시도 — 관찰이 목적이다

충돌하면(`ObjectOptimisticLockingFailureException`) 재시도한다.
재시도 상한은 **100회**, 백오프 없음.

상한을 두는 이유는 무한 루프 방지다. 100이라는 값에 근거는 없고,
**실제로 몇 번 재시도가 일어나는지 세어서 기록하는 것이 목적이다.**

1000 스레드가 100장을 놓고 경쟁하면 충돌이 심할 것으로 예상한다.
재시도 총 횟수가 발급 건수보다 훨씬 크게 나오면 그것이 낙관적 락의
적용 한계를 보여주는 수치다. 상한 100회로도 부족하면 그 사실을 기록한다.

백오프를 넣지 않는 이유: 넣으면 "재시도가 몇 번 필요한가"가 아니라
"백오프를 얼마나 잘 튜닝했는가"를 재게 된다. 튜닝은 이 Phase의 목적이 아니다.

### 4.6 gap lock은 별도 관찰 실험으로

4.2의 비관적 락(PK 단일 행)에서는 gap lock이 생기지 않는다.
그런데 MySQL을 선택한 이유 중 하나가 gap lock 관찰이었다.

gap lock을 보려면 **세컨더리 인덱스에 범위 락**을 걸어야 한다.

```sql
select * from coupon_issue where coupon_id = ? for update
```

이건 억지 시나리오가 아니다. "발급 이력을 잠그고 세자"는 꽤 자연스러운 발상이고,
`idx_coupon_issue_coupon_id`에 next-key lock이 걸리면서
**같은 쿠폰에 대한 동시 insert가 서로를 막는다.**

이것을 구현체로 만들지 않는다. k6로 측정하지도 않는다.
**테스트 하나짜리 관찰 실험**으로 만들어 데드락 또는 락 대기가 발생하는지 보고,
`SHOW ENGINE INNODB STATUS`의 `LATEST DETECTED DEADLOCK` 섹션과
`performance_schema.data_locks`를 떠서 `docs/known-issues.md`에 기록한다.

데드락이 재현되지 않으면 그것도 기록한다. 재현 실패를 성공으로 포장하지 않는다.

### 4.7 unique 제약 측정 순서

`(coupon_id, user_id)` unique 제약의 효과를 보려면 적용 전후를 비교해야 하는데,
마이그레이션은 되돌릴 수 없다. 순서로 해결한다.

1. 중복 발급 테스트를 먼저 작성하고 **제약 없는 상태에서** 실행해 수치를 기록한다
2. 그 다음 unique 제약 마이그레이션을 추가한다
3. 같은 테스트를 다시 실행해 1건이 되는지 확인한다

TDD의 red-green과 같은 흐름이고, 문서에는 "제약 적용 전 측정값"으로 남긴다.

제약이 걸린 뒤 나이브 구현이 중복 요청을 받으면
`DataIntegrityViolationException`이 발생한다. 이걸 잡아서 `false`를 돌릴지,
그대로 터뜨릴지는 6.4절에서 정한다.

### 4.8 `innodb_lock_wait_timeout`

기본값 50초다. 비관적 락에서 1000 스레드가 대기 큐에 쌓이면
테스트가 오래 끌릴 수 있다.

**먼저 기본값으로 측정한다.** 커넥션 풀이 10이라 실제 대기 큐는 짧을 것으로 예상한다.
테스트가 눈에 띄게 느리거나 타임아웃이 발생하면 값을 낮추고
그 사실을 측정 조건에 기록한다. 미리 바꾸지 않는다.

## 5. 스키마 변경

### `V2__optimistic_lock.sql`

```sql
alter table coupon
    add column issued_quantity int    not null default 0,
    add column version         bigint not null default 0;
```

`issued_quantity`는 낙관적 락 구현만 사용한다.
`version`은 Hibernate `@Version`이 관리한다.

### `V3__unique_coupon_user.sql`

```sql
alter table coupon_issue
    add constraint uk_coupon_issue_coupon_user unique (coupon_id, user_id);
```

**주의: 이 인덱스가 `idx_coupon_issue_coupon_id`를 대체할 수 있다.**
`(coupon_id, user_id)` unique 인덱스의 선두 컬럼이 `coupon_id`이므로
`countByCouponId()`가 이 인덱스를 탈 수 있다.
기존 인덱스를 지우지는 않되, **실행 계획이 바뀌는지 `EXPLAIN`으로 확인하고 기록한다.**
인덱스가 바뀌면 Phase 1과 Phase 2의 `count()` 비용이 달라져 비교가 흔들린다.

## 6. 상세 설계

### 6.1 `Coupon` 엔티티

추가할 필드.

```java
@Column(nullable = false)
private int issuedQuantity;

@Version
private long version;
```

추가할 메서드 — 낙관적 락 구현만 호출한다.

```java
public boolean tryIssue() {
    if (issuedQuantity >= totalQuantity) {
        return false;
    }
    issuedQuantity++;
    return true;
}
```

도메인 로직을 엔티티에 둔다. 재고 초과 판단은 `Coupon`이 스스로 한다.

### 6.2 리포지토리

`CouponRepository`에 비관적 락 조회를 추가한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Coupon c where c.id = :id")
Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
```

`CouponIssueRepository`는 gap lock 실험용 메서드를 추가한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select ci from CouponIssue ci where ci.couponId = :couponId")
List<CouponIssue> findByCouponIdForUpdate(@Param("couponId") Long couponId);
```

### 6.3 구현체

**`NaiveCouponIssuer`** — Phase 1의 `CouponIssueService`를 이름만 바꾼 것.
로직은 건드리지 않는다. Phase 1 수치의 기준선이 유지되어야 한다.

**`PessimisticLockCouponIssuer`**

```
@Transactional
1) couponRepository.findByIdForUpdate(couponId)   ← 여기서 직렬화된다
2) countByCouponId()
3) issued >= totalQuantity 이면 false
4) save()
5) true
```

**`OptimisticLockCouponIssuer`**

```
재시도 루프 (상한 100)
  @Transactional 메서드 호출
    1) couponRepository.findById()
    2) coupon.tryIssue() 가 false 면 품절 반환 (재시도 안 함)
    3) couponIssueRepository.save()
    4) 트랜잭션 커밋 시 version 충돌이면 예외
  ObjectOptimisticLockingFailureException 이면 재시도
```

**재시도 루프는 트랜잭션 밖에 있어야 한다.**
`@Transactional` 안에서 잡으면 이미 롤백 표시된 트랜잭션을 재사용하게 된다.
따라서 재시도를 담당하는 클래스와 트랜잭션 경계를 갖는 클래스를 분리한다.
자기 자신을 호출하면 프록시를 타지 않아 `@Transactional`이 걸리지 않는다.

재시도 횟수는 `AtomicLong`으로 누적해 테스트에서 읽는다.

### 6.4 unique 제약 위반을 잡지 않는다

V3 적용 후, 세 구현 모두 같은 유저의 두 번째 요청에서
`DataIntegrityViolationException`을 만난다. **이걸 잡지 않는다.**

처음에는 잡아서 `false`를 돌리려 했으나 두 가지 이유로 접었다.

**첫째, 잡을 필요가 없다.** 중복 발급 테스트가 확인하는 것은
"발급 행이 1건인가"이고, 예외가 나가든 `false`가 돌아오든 행은 1건이다.
테스트는 예외 수를 따로 세어 기록하므로 정보가 사라지지도 않는다.

**둘째, 나이브 구현을 건드리게 된다.** `NaiveCouponIssuer`는 Phase 1 수치의
기준선이므로 로직을 바꾸면 안 된다. 세 구현 중 둘만 잡으면 동작이 갈려
매트릭스를 읽기 어려워진다.

그래서 Phase 2의 결과는 이렇게 정리된다.

> **unique 제약은 데이터 정합성을 지키지만 API 계약은 개선하지 않는다.**
> 중복 요청은 500으로 나간다. 이걸 "이미 발급됨"으로 응답하게 만드는 것은
> 멱등성 설계의 문제이고, 로드맵에서 Phase 4의 주제다.

지금 잡으면 Phase 4에서 다룰 멱등성 논의가 절반쯤 미리 소모된다.

### 6.5 컨트롤러

변경은 주입 타입뿐이다.

```java
private final CouponIssuer couponIssuer;
```

엔드포인트, 요청 본문, 상태코드(200/409)는 그대로 둔다.
Phase 1의 k6 스크립트를 그대로 재사용해야 비교가 성립한다.

### 6.6 테스트

**재고 경합 테스트** — Phase 1의 `CouponIssueConcurrencyTestBase`를 재사용한다.
주입 타입을 `CouponIssuer`로 바꾸고 프로파일별 구체 클래스를 둔다.
Phase 1의 두 클래스는 이름을 바꾼다 — 이제 "동시성 테스트"가 두 종류라
`Concurrency`라는 이름만으로는 무엇을 재는지 알 수 없다.

| 클래스 | 이전 이름 | 프로파일 | 기대 |
|---|---|---|---|
| `NaiveStockRaceTest` | `CouponIssueConcurrencyTest` | `naive` | 실패 (Phase 1의 107건) |
| `NaiveStockRaceLargePoolTest` | `CouponIssueConcurrencyLargePoolTest` | `naive` | 실패 (Phase 1의 126건) |
| `PessimisticLockStockRaceTest` | 신규 | `pessimistic` | 통과 (100건) |
| `OptimisticLockStockRaceTest` | 신규 | `optimistic` | 통과 (100건) + 재시도 횟수 출력 |

`StockRaceTestBase`가 시나리오 본문을 갖고, 위 네 클래스는 프로파일과
풀 크기만 다르게 상속한다. Phase 1에서 만든 구조 그대로다.

낙관적 락 테스트는 `issued_quantity` 컬럼과 실제 행 개수가 일치하는지도
함께 확인한다 (4.4절의 진실의 원천 이중화 문제).

**중복 발급 테스트** — 신규 `DuplicateIssueTest`.

```
쿠폰 100장, 유저 1명이 1000번 동시 요청
최종 발급 행이 1건인지 확인
예외 수를 세어 출력한다
```

| 시점 | 프로파일 | 기대 |
|---|---|---|
| V3 적용 전 | `naive` | 실패 — 재고 한도(100건)까지 발급됨 |
| V3 적용 후 | `naive` | 통과 — 1건 + 예외 다수 |
| V3 적용 후 | `pessimistic` | 통과 — 1건 |

**gap lock 관찰 실험** — `GapLockObservationTest`.
`findByCouponIdForUpdate()`로 범위 락을 잡는 시나리오를 2~4 스레드로 돌리고,
데드락 또는 락 대기 타임아웃이 발생하는지 본다.
발생 시 `SHOW ENGINE INNODB STATUS` 출력을 파일로 떠서 기록한다.

이 테스트는 **통과/실패를 단정하지 않는다.** 관찰이 목적이므로
결과를 출력하고 항상 통과시킨다. 관찰 결과는 문서에 남긴다.

### 6.7 에러 처리

여전히 전역 예외 핸들러를 만들지 않는다.

| 예외 | 처리 |
|---|---|
| `DataIntegrityViolationException` (unique 위반) | 잡지 않는다 (6.4절). 500으로 나간다 |
| `ObjectOptimisticLockingFailureException` | 낙관적 락 구현이 재시도, 상한 초과 시 `false` 반환 |

낙관적 락의 재시도만 예외를 잡는다. 그건 방어 코드가 아니라 낙관적 락의
동작 그 자체다 — 충돌을 감지하고 다시 시도하는 것이 이 기법의 정의다.

## 7. 측정 계획

### 정합성 (JUnit + Testcontainers)

조건은 Phase 1과 동일하다 — 쿠폰 100장, 1000 스레드, 커넥션 풀 10.

낙관적 락은 재시도 총 횟수를 함께 기록한다.

### 부하 (k6)

Phase 1과 완전히 동일한 조건이다 — 쿠폰 100만장, VU 200, 30초, 풀 10.
`load-test/phase1-issue.js`를 그대로 쓴다.

측정은 3회. 매회 `docker compose down -v` → `up -d` → 프로파일 지정 후 앱 기동 →
쿠폰 insert → k6 실행.

```
ISSUE_STRATEGY=naive       (Phase 1 값 재확인)
ISSUE_STRATEGY=pessimistic
ISSUE_STRATEGY=optimistic
```

나이브를 다시 재는 이유는 **V2/V3 스키마 변경이 Phase 1 수치에 영향을 주는지**
확인하기 위해서다. 5절에서 지적한 인덱스 변경 가능성이 있다.
값이 유의미하게 달라졌으면 Phase 1 수치와 나란히 적고 원인을 밝힌다.

### 결과물

`docs/benchmark.md`에 아래를 추가한다.

- 결과 표에 Phase 2 세 행
- 정합성 매트릭스 (2절의 표를 실측으로 채운 것)
- 트레이드오프 표 — 정합성 / 성능 / 구현 복잡도 / 한계

## 8. 완료 조건

1. 재고 경합 테스트가 비관적·낙관적 락에서 정확히 100건으로 통과한다
2. 중복 발급 테스트가 unique 제약 적용 후 1건으로 통과한다
3. 2절의 매트릭스가 실측값으로 채워진다
4. k6 3회 측정이 완료되고 **비관적·낙관적 락의 TPS가 나이브보다 낮다**
   (낮지 않으면 부하 부족이므로 조건을 재검토한다)
5. 낙관적 락의 재시도 총 횟수가 기록된다
6. gap lock 관찰 결과가 `docs/known-issues.md`에 기록된다 (재현 실패 포함)
7. 트레이드오프 표가 작성된다
8. `origin/main`에 push 완료

## 9. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 비관적 락 TPS가 나이브와 비슷하거나 높음 | Phase 3의 근거 상실 | 부하 부족이 원인일 가능성이 크다. VU를 올려 재측정하고 조건 변경을 기록한다. `count()`가 이미 병목이라 락 비용이 묻히는 것일 수도 있으므로 그 가설도 확인한다 |
| 낙관적 락이 재시도 100회로도 100장을 못 채움 | 정합성 테스트 실패 | 그 자체가 결과다. 상한을 올려 필요한 횟수를 찾고 "이 경합 수준에서 낙관적 락은 부적합"을 수치로 기록한다 |
| gap lock 데드락이 재현되지 않음 | 학습 목표 미달 | 스레드 수와 트랜잭션 순서를 바꿔 시도한다. 그래도 안 되면 재현 실패와 시도한 조건을 기록한다 |
| V3 unique 인덱스가 실행 계획을 바꿈 | Phase 1↔2 비교 흔들림 | `EXPLAIN`으로 확인하고, 바뀌었으면 나이브 재측정값을 기준으로 비교한다 |
| 낙관적 락에서 `issued_quantity`와 행 개수가 어긋남 | 정합성 구멍 | 테스트에서 두 값의 일치를 함께 검증한다. 어긋나면 원인을 찾아 기록한다 |

## 10. 다음 Phase로 넘기는 항목

- **Phase 3**: Redis. 재고를 카운터로 옮기면 `count()` 비용과 락 경합이 동시에 사라진다.
  Phase 2의 TPS 하락 폭이 그 개선의 크기를 재는 기준이 된다.
  Redisson 분산 락은 비관적 락의 분산 버전이므로 Phase 2 수치와 직접 비교한다.
- **Phase 4**: unique 제약이 Kafka 컨슈머의 멱등성 보장 수단으로 다시 등장한다.
