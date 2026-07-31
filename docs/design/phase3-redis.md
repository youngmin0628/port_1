# Phase 3 — Redis 원자 연산 설계

작성일: 2026-07-31

## 1. 목표

재고 차감과 중복 발급 방지를 **Redis에서 단일 원자 연산으로** 처리한다.

Phase 2에서 정합성을 얻는 대가로 처리량의 4분의 3(비관적 218)에서
6분의 5(낙관적 114)를 잃었다. 그 손실을 되찾으면서 오버셀 0건을 유지하는 것이 목표다.

동시에 **새로운 결함이 생긴다.** Redis와 DB가 서로 다른 저장소이므로
둘에 원자적으로 쓸 수 없다. 이 구멍을 인지하고 기록하는 것도 이 Phase의 산출물이다.

## 2. 설계 질문에 대한 답

구현 전에 답해야 할 세 가지다.

### 2.1 `INCR` 후 초과분 롤백과 Lua 스크립트의 차이는?

**결론부터: 롤백 자체가 문제가 아니라, 롤백을 스크립트 밖에서 하는 것이 문제다.**

`INCR` 후 롤백 방식은 이렇게 생겼다.

```
issued = INCR coupon:1:issued        (왕복 1)
if issued > total:
    DECR coupon:1:issued             (왕복 2)
    return 품절
```

이것만 놓고 보면 최종 발급 수는 한도를 넘지 않는다. `INCR`이 원자적이라
두 요청이 같은 값을 받는 일이 없기 때문이다. Phase 1의 read-modify-write 문제는 사라진다.

문제는 세 가지다.

**첫째, 중복 발급 방지를 함께 못 한다.** 중복까지 막으려면
`SISMEMBER` → `INCR` → `SADD` 세 연산이 필요한데, 이건 원자적이지 않다.
`SISMEMBER`가 "없음"을 반환한 뒤 `SADD` 전에 같은 유저의 다른 요청이 끼어들 수 있다.
**Phase 1에서 재현한 read-modify-write 문제가 Redis로 자리만 옮겨 다시 나타난다.**

**둘째, 롤백 사이에 앱이 죽으면 재고가 영구히 샌다.**
`INCR`은 됐는데 `DECR`을 못 하면 카운터가 실제 발급 수보다 큰 채로 남는다.
오버셀은 아니지만 **팔 수 있는 재고를 못 파는 손실**이고, 재시작해도 저절로 복구되지 않는다.

**셋째, 왕복이 늘어난다.** 품절 구간에서는 매 요청이 2회 왕복한다.

Lua 스크립트는 Redis가 **단일 원자 단위로 실행**한다.
Redis는 명령 처리가 단일 스레드이고 스크립트 실행 중에는 다른 명령이 끼어들지 않는다.
따라서 스크립트 안에서는 `SISMEMBER` → `INCR` → `SADD`를 마음껏 조합해도
중간 상태를 아무도 관찰하지 못한다. 부분 적용도 없다.

**스크립트 안에서 하는 `INCR` 후 `DECR`은 안전하다.**
스크립트가 끝날 때까지 그 중간값을 볼 수 있는 관찰자가 없기 때문이다.
그래서 이 프로젝트의 Lua 스크립트도 초과 시 `DECR`로 되돌린다 — 방식은 같지만
경계가 다르다.

**선택: Lua.** 결정적인 이유는 첫째다.
재고 차감과 중복 방지를 함께 원자화해야 하는데 `INCR` 롤백 방식으로는 불가능하다.

**Lua의 대가도 적어둔다.**

- Redis는 단일 스레드다. 스크립트가 오래 걸리면 **Redis 전체가 멈춘다.**
  스크립트는 짧게 유지해야 하고, 루프를 돌리면 안 된다.
- Redis Cluster에서는 스크립트가 만지는 키가 **같은 슬롯**에 있어야 한다.
  이 프로젝트는 단일 노드지만 키 이름에 hash tag를 넣어 대비한다 (4.3절).
- 스크립트 디버깅이 애플리케이션 코드보다 불편하다.

### 2.2 중복 발급 방지를 Redis Set으로 할 때 메모리는? 대안은?

`SADD coupon:1:users <userId>`로 발급받은 유저를 모은다.

**인코딩에 따라 크기가 크게 갈린다.**

| 조건 | 인코딩 | 대략 크기 |
|---|---|---|
| 정수만, 512개 이하 (`set-max-intset-entries` 기본값) | intset | 항목당 8바이트, 정렬 배열 |
| 그 이상 | hashtable | **항목당 60~90바이트** |

hashtable로 전환되면 항목마다 `dictEntry` + 값 객체 + 버킷 몫이 붙는다.
100만 명이면 **80~90MB 수준**이고, 쿠폰이 여러 개면 그 배수다.

**대안 넷을 검토했다.**

| 대안 | 크기 | 판단 |
|---|---|---|
| `SET NX` 키 하나씩 (`issued:1:42`) | 키당 100바이트 이상, Set보다 크다 | **유저별 TTL을 걸 수 있는 것이 유일한 장점.** 크기는 오히려 불리 |
| Bitmap (`SETBIT coupon:1:users <userId> 1`) | 유저 1억 명이 12.5MB | userId가 조밀한 정수면 **압도적으로 작다.** sparse하면 최대 userId까지 할당돼 낭비 |
| HyperLogLog | 12KB 고정 | 개수만 세고 **멤버십 판정을 못 한다.** 부적합 |
| Bloom filter | 매우 작음 | false positive가 "이미 받았다"는 오판이 된다. 안 받은 사람을 거절하는 건 치명적. 부적합 |

**선택: Set을 그대로 쓴다.** 이 프로젝트 규모에서 80MB는 문제가 아니고,
Bitmap은 userId가 조밀하다는 가정이 필요한데 그 가정을 세울 근거가 지금 없다.

**다만 결정적인 사실 하나를 기록해둔다.**
Phase 2에서 `(coupon_id, user_id)` unique 제약을 이미 넣었다.
따라서 **Redis Set은 정합성의 최후 보루가 아니다.** 최후 보루는 DB 제약이고,
Redis Set은 DB까지 가지 않고 빠르게 거절하기 위한 1차 거름망이다.

이 구분이 중요한 이유는, 만약 Redis가 유실되거나 Set이 만료돼도
**중복 발급이 실제로 일어나지는 않기 때문이다.** DB가 막는다.
느려질 뿐이다. 그래서 Set의 정확성에 목숨을 걸 필요가 없고,
나중에 Bitmap이나 Bloom filter로 바꾸는 선택지도 열려 있다.

**메모리 회수는 이번에 하지 않는다.** TTL을 걸려면 "언제까지 유효한가"를 알아야 하는데
`Coupon`에 이벤트 종료 시각이 없다. 도메인에 없는 개념을 만들어 넣기보다
`docs/known-issues.md`에 "키가 무한히 쌓인다"로 남긴다.

### 2.3 Redis에서 재고 차감 성공 직후 앱이 죽으면?

**상태:** Redis 카운터는 증가했고 Set에 유저가 등록됐는데, DB에는 발급 행이 없다.

**결과: 과소 판매(under-sell).**

- 100장 중 1장이 영원히 안 나간다. 카운터는 그 자리를 이미 소진된 것으로 센다.
- 더 나쁜 건 그 유저다. 재시도해도 **Set에 이미 있어서 "중복"으로 거절**된다.
  재고는 자기 몫으로 까였는데 쿠폰은 못 받은 상태로 갇힌다.

오버셀보다는 낫다. 재고를 초과 발급하는 것보다 덜 파는 쪽이 사업적으로 안전하다.
하지만 결함이 사라진 게 아니라 **성격이 바뀐 것**이다.

**근본 원인:** Redis와 DB는 서로 다른 저장소이고,
둘에 걸친 원자적 쓰기는 존재하지 않는다. 어느 쪽을 먼저 쓰든 그 사이에 죽을 수 있다.

**Phase 3에서 해결하지 않는다.** 인지하고 `docs/known-issues.md`에 기록한다.

Phase 4에서 Kafka를 넣으면 **이 창이 오히려 넓어진다.**
Redis 성공 → Kafka produce 실패, 또는 produce 성공 → 컨슈머 DB 저장 실패까지
실패 지점이 늘어난다. 그래서 Phase 4에 보상 전략이 필수 항목으로 들어 있다.

알려진 해결 계열은 셋이고, 어느 것도 공짜가 아니다.

| 전략 | 방식 | 대가 |
|---|---|---|
| 보상 트랜잭션 | DB 실패 시 Redis를 되돌린다 | **앱이 죽으면 되돌릴 주체가 없다.** 이번 시나리오를 못 막는다 |
| Outbox 패턴 | DB 트랜잭션 안에 이벤트를 같이 저장하고 별도 프로세스가 발행 | 재고 판정이 DB로 돌아와 Redis를 쓰는 의미가 줄어든다 |
| 정합성 대조 배치 | Redis 카운터와 DB 행 수를 주기적으로 비교해 보정 | 창이 배치 주기만큼 열려 있다. 가장 현실적 |

## 3. 범위

### 하는 것

- `spring-boot-starter-data-redis` 의존성 추가
- Lua 스크립트 — 중복 체크 + 재고 차감을 단일 원자 연산으로
- `RedisCouponIssuer` (`@Profile("redis")`)
- Testcontainers Redis를 쓰는 테스트 베이스
- 재고 경합 테스트 (100건 통과)
- 중복 발급 테스트 — **DB까지 가지 않고 걸러지는지**를 예외 0건으로 검증
- k6 측정 1회 (동일 조건)
- `docs/benchmark.md`, `docs/progress.md`, `docs/known-issues.md` 갱신

### 하지 않는 것

| 항목 | 왜 안 하는가 |
|---|---|
| Redisson 분산 락 비교 | 선택 과제다. 프로젝트 완료 조건에 없다. Phase 4를 먼저 끝낸다 |
| Kafka | Phase 4 |
| Redis 키 TTL | 2.2절. `Coupon`에 이벤트 종료 시각이 없어 정할 근거가 없다 |
| 보상 트랜잭션 / Outbox / 대조 배치 | 2.3절. Phase 4의 주제다 |
| 쿠폰 재고의 Redis 사전 적재 | 4.2절 |
| 관리 API | 여전히 만들지 않는다 |

## 4. 결정 사항과 근거

### 4.1 재고 한도는 여전히 DB에서 읽는다

Lua 스크립트는 DB를 모른다. 한도(`totalQuantity`)를 어디선가 받아야 한다.

**`couponRepository.findById()`로 읽어 스크립트 인자로 넘긴다.**
Phase 1과 Phase 2도 매 요청 `findById()`를 했으므로, 이렇게 하면
**Phase 1~3에서 달라지는 것이 `count()` → Redis 카운터 하나뿐**이 된다.
Phase 2에서 `count()`를 유지해 "락의 대가만 분리"했던 것과 같은 원칙이다.

**대가를 정직하게 적는다.** 이 구조에서는 DB 왕복이 요청당 1회 남는다.
실서비스라면 쿠폰 메타를 캐시하거나 이벤트 시작 시 재고를 Redis에 적재해
DB 왕복을 0으로 만든다. 그러면 더 빨라진다.

즉 **이 프로젝트가 측정할 Redis 수치는 Redis 방식의 상한이 아니라 하한이다.**
이 사실을 `docs/benchmark.md`에 적는다.

### 4.2 재고를 Redis에 사전 적재하지 않는다

`SET coupon:1:stock 100` 후 `DECR`로 깎는 방식이 정석에 가깝고 더 빠르다.
하지만 **"누가 언제 적재하는가"** 라는 운영 문제가 따라온다.

- 발급 시 없으면 초기화 → 그 초기화 자체가 경합 지점이 된다
- 관리 API로 적재 → 요청되지 않은 기능이다
- 앱 기동 시 전량 적재 → 쿠폰이 많아지면 기동이 느려지고, 앱이 여러 대면 중복 적재된다

Redis에는 **발급 수 카운터만** 둔다. 0에서 시작해 `INCR`로 올리고,
한도는 4.1의 인자로 비교한다. 키가 없으면 `INCR`이 1을 만들므로 초기화가 필요 없다.

### 4.3 키 이름에 hash tag를 넣는다

```
{coupon:1}:issued    발급 수 카운터
{coupon:1}:users     발급받은 유저 Set
```

Redis Cluster는 Lua 스크립트가 만지는 키가 **같은 슬롯**에 있을 것을 요구한다.
중괄호로 감싼 부분만 해싱하므로 위 두 키는 항상 같은 노드에 놓인다.

지금은 단일 노드라 아무 효과가 없다. 그래도 넣는 이유는
**키 이름은 나중에 바꾸기 어렵고 지금 넣는 비용이 0이기 때문이다.**
운영 중에 키 스키마를 바꾸려면 마이그레이션이 필요하다.

### 4.4 Lua 스크립트

```lua
-- KEYS[1] = 발급 수 카운터, KEYS[2] = 발급자 Set
-- ARGV[1] = userId, ARGV[2] = totalQuantity
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local issued = redis.call('INCR', KEYS[1])
if issued > tonumber(ARGV[2]) then
    redis.call('DECR', KEYS[1])
    return 0
end

redis.call('SADD', KEYS[2], ARGV[1])
return 1
```

| 반환값 | 의미 |
|---|---|
| `1` | 발급 성공 |
| `0` | 품절 |
| `-1` | 이미 발급받은 유저 |

`INCR` 후 초과 시 `DECR`로 되돌리는 것은 2.1절에서 말한 그 방식이지만,
**스크립트 안이라 중간 상태를 관찰할 수 있는 주체가 없다.**

스크립트는 `RedisTemplate.execute(RedisScript, keys, args)`로 실행한다.
Spring Data Redis가 `EVALSHA`를 먼저 시도하고 캐시에 없으면 `EVAL`로 넘어간다.

### 4.5 트랜잭션 경계 — Redis 호출은 `@Transactional` 밖

이 프로젝트가 처음부터 지켜온 규칙이고, 여기서 처음으로 실제 위험이 된다.

```
1) couponRepository.findById()      DB 읽기
2) Lua 스크립트 실행                 외부 호출 — 트랜잭션 밖
3) 성공이면 DB insert               @Transactional
```

DB 트랜잭션 안에서 Redis를 호출하면, Redis가 느려지는 동안 DB 커넥션과
트랜잭션이 붙잡혀 있다. 커넥션 풀 10개짜리 환경에서는 치명적이다.

그래서 `RedisCouponIssuer.issue()`에는 `@Transactional`을 붙이지 않고,
DB insert만 별도 컴포넌트에 위임한다. Phase 2의 낙관적 락에서
재시도 루프와 트랜잭션을 분리한 것과 같은 구조다.

### 4.6 헬스체크에서 Redis를 제외한다

`spring-boot-starter-data-redis`가 클래스패스에 있으면 `RedisHealthIndicator`가
프로파일과 무관하게 동작한다. `naive`나 `pessimistic` 프로파일로 돌 때
Redis가 없으면 `/actuator/health`가 `DOWN`이 되고,
Phase 0에서 만든 헬스체크 테스트가 깨진다.

```yaml
management:
  health:
    redis:
      enabled: false
```

Redis는 `redis` 프로파일에서만 쓰는데 헬스체크는 프로파일을 모른다.
자동 설정 자체를 프로파일별로 끄는 방법도 있지만 설정이 복잡해진다.
한 줄로 끄고 이유를 남기는 편이 낫다.

## 5. 상세 설계

### 5.1 파일 구조

| 파일 | 책임 |
|---|---|
| `issue/RedisCouponIssuer.java` | Lua 실행 + DB 저장 위임. `@Profile("redis")` |
| `issue/CouponIssueRecorder.java` | DB insert만 담당. `@Transactional` 경계 |
| `resources/redis/coupon-issue.lua` | 4.4절 스크립트 |
| `issue/RedisScriptConfig.java` | `RedisScript<Long>` 빈 등록 |
| `test/.../support/RedisTestBase.java` | `MySqlTestBase`를 상속, Redis 컨테이너 추가 |
| `test/.../issue/RedisStockRaceTest.java` | 재고 경합 |
| `test/.../issue/DuplicateIssueTestBase.java` | 기존 `DuplicateIssueTest`에서 추출 |
| `test/.../issue/NaiveDuplicateIssueTest.java` | 개명 |
| `test/.../issue/RedisDuplicateIssueTest.java` | 예외 0건 검증 |

### 5.2 `RedisCouponIssuer`

```
issue(couponId, userId):
    coupon = couponRepository.findById(couponId).orElseThrow()
    result = redisTemplate.execute(script, keys(couponId), userId, coupon.getTotalQuantity())
    if result != 1: return false
    recorder.record(couponId, userId)
    return true
```

품절과 중복을 모두 `false`로 접는다. `CouponIssuer` 계약이 `boolean`이고,
호출자가 둘을 구분해야 할 이유가 아직 없다. 구분이 필요해지는 시점은
멱등 응답을 설계하는 Phase 4다.

### 5.3 `CouponIssueRecorder`

```java
@Transactional
public void record(Long couponId, Long userId) {
    couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
}
```

이것만 한다. Redis 호출과 DB 쓰기의 경계를 클래스로 갈라 두면
"트랜잭션 안에서 외부 호출을 하지 않는다"가 코드 구조로 보장된다.

### 5.4 테스트

**재고 경합** — `RedisStockRaceTest`. 쿠폰 100장, 1000 스레드, 100건 통과.

**중복 발급** — 여기가 Phase 3에서 새로 얻는 검증이다.

| 프로파일 | 발급 | 예외 | 의미 |
|---|---|---|---|
| `naive` | 1건 | 999건 | DB unique 제약이 막는다. 요청이 DB까지 간다 |
| `redis` | 1건 | **0건** | Redis Set이 먼저 거른다. DB까지 가지 않는다 |

**예외 0건이 Redis Set의 가치를 보여주는 수치다.**
정합성은 둘 다 확보되지만, 비용이 다르다.

기존 `DuplicateIssueTest`를 `DuplicateIssueTestBase`로 바꾸고
프로파일별 구체 클래스를 둔다. Phase 2에서 `StockRaceTestBase`에 한 것과 같다.

**Testcontainers Redis** — `GenericContainer("redis:7.4-alpine")`,
`MySqlTestBase`와 같은 싱글턴 패턴. `@DynamicPropertySource`로
`spring.data.redis.host`, `spring.data.redis.port`를 넘긴다.

### 5.5 에러 처리

전역 예외 핸들러는 여전히 만들지 않는다.

Redis 연결 실패 시 `RedisConnectionFailureException`이 그대로 올라가 500이 된다.
잡지 않는다. **Redis가 죽으면 발급이 멈추는 것이 맞다.**
잡아서 DB 경로로 폴백하면 오버셀이 다시 열린다.
이 선택을 `docs/known-issues.md`에 적는다.

## 6. 측정 계획

Phase 2와 완전히 동일한 조건이다. `load-test/phase1-issue.js`를 그대로 쓴다.

측정 전 Redis를 함께 띄운다. profile로 격리해 뒀으므로 명령에 profile을 붙여야 한다.

```
docker compose --profile redis down -v
docker compose --profile redis up -d
```

**Redis도 반드시 비워야 한다.** 이전 실행의 카운터와 Set이 남아 있으면
첫 요청부터 품절이 되어 측정이 통째로 무의미해진다.
Redis 서비스에는 named volume이 없으므로 컨테이너를 지우면 데이터도 사라진다.
`--profile redis`를 빠뜨리면 Redis 컨테이너가 정리되지 않으니 주의한다.

측정 결과에서 `coupon_sold_out`이 0이 아니면 Redis가 안 비워진 것을 먼저 의심한다.

## 7. 완료 조건

1. 재고 경합 테스트가 정확히 100건으로 통과한다
2. 중복 발급 테스트가 `redis` 프로파일에서 **발급 1건 / 예외 0건**으로 통과한다
3. k6 측정이 완료되고 **TPS가 Phase 2의 락 방식(218 / 114)보다 높다**
4. 2.3절의 정합성 구멍이 `docs/known-issues.md`에 기록된다
5. Redis 키가 무한히 쌓이는 문제가 `docs/known-issues.md`에 기록된다
6. `docs/benchmark.md`에 Redis 행이 추가된다
7. `origin/main`에 push 완료

## 8. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| Redis TPS가 나이브(819)보다 낮음 | Phase 3의 근거 상실 | 4.1의 DB 왕복이 병목일 수 있다. 그 경우 "Redis 방식의 하한"임을 명시하고, 재고 사전 적재 시 어떻게 달라지는지를 별도 측정으로 확인할지 판단한다 |
| Lua 스크립트가 `EVAL`로 매번 전송됨 | 대역폭 낭비, TPS 저하 | Spring Data Redis는 `EVALSHA`를 먼저 쓴다. `MONITOR`나 `INFO commandstats`로 확인한다 |
| Redis 자동 설정이 다른 프로파일 측정에 영향 | Phase 1/2 재현 불가 | 4.6절로 헬스체크만 끈다. 연결은 lazy라 실제 명령이 없으면 열리지 않는다 |
| Set이 커져 메모리 부족 | 측정 중단 | 부하 테스트 30초에 3만 건 수준이라 수 MB다. 문제되지 않는다 |

## 9. 다음 Phase로 넘기는 항목

- **Phase 4**: Kafka 비동기화. 2.3절의 정합성 구멍이 넓어지므로 보상 전략이 필수다.
  Redis Set은 Kafka 컨슈머의 멱등성과 역할이 겹치므로 관계를 정리해야 한다.
  DB insert가 비동기가 되면 4.1의 DB 왕복도 사라진다.
- **선택 과제**: Redisson 분산 락과 Lua 스크립트의 성능·특성 비교.
  프로젝트 완료 조건에는 없다.
