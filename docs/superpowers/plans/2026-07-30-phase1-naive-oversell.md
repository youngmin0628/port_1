# Phase 1 나이브 구현 + 오버셀 재현 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 락 없는 `count()` → 비교 → `save()` 발급 로직을 만들고, 1000 스레드 동시 요청에서 오버셀이 실제로 발생하는 것을 수치로 남긴다.

**Architecture:** `domain` 패키지가 엔티티와 리포지토리를 갖고, `issue` 패키지가 발급 흐름을 갖는다. 서비스는 `@Transactional` 안에서 발급 수를 세고 재고와 비교한 뒤 저장한다. 락·캐시·큐는 없다. 취약 구간은 `count()`와 `save()` 커밋 사이이며, 이것이 Phase 1의 관찰 대상이다.

**Tech Stack:** Java 21 / Spring Boot 3.5.16 / Gradle 8.14.3 / JPA / MySQL 8.4 / Testcontainers / k6 v2.1.0

## Global Constraints

스펙: `docs/superpowers/specs/2026-07-30-phase1-naive-oversell-design.md`

- **어떤 형태의 락도 넣지 않는다.** `SELECT FOR UPDATE`, `@Version`, `synchronized`, unique 제약, Redis, Kafka 전부 금지. Phase 2 이후의 재료다.
- **중복 발급 방지를 넣지 않는다.** Phase 1은 재고 경합만 재현한다.
- **동시성 테스트는 실패하는 상태로 커밋한다.** 통과시키려고 고치지 않는다.
- **전역 예외 핸들러 / `@RestControllerAdvice` / 요청 검증(`@Valid`)을 만들지 않는다.**
- **재시도 로직을 만들지 않는다.** Phase 2 낙관적 락에서 처음 필요해진다.
- **인터페이스 추상화를 만들지 않는다.** 구현체가 2개 이상 필요해지는 Phase 2에서 추출한다.
- **쿠폰 생성 API를 만들지 않는다.** k6용 쿠폰은 SQL insert로 만든다.
- 서비스 반환 타입은 `boolean`. 품절은 예외가 아니라 결과다.
- HTTP 상태코드: 발급 성공 200, 품절 **409 Conflict**
- 엔드포인트: `POST /coupons/{couponId}/issues`, 본문 `{"userId": 1}`
- getter는 읽는 곳이 있는 것만 만든다. `Coupon.getName()`은 만들지 않는다.
- `issuedAt`은 생성자 인자로 받는다. 엔티티 안에서 `LocalDateTime.now()`를 부르지 않는다.

**측정 조건 (스펙 3절 — Phase 0의 단일 조건을 둘로 쪼갠 것)**

| | 정합성 테스트 (JUnit) | 부하 테스트 (k6) |
|---|---|---|
| 쿠폰 총 수량 | 100 | 1,000,000 |
| 부하 | 1000 스레드 | VU 200, 30s |
| 측정 대상 | 최종 발급 건수 (= 오버셀 규모) | TPS, p95, p99 |

- HikariCP 기본 풀 크기 **10**. 오버셀 측정은 풀 **10**과 **50** 두 번 한다.
- 부하 테스트 전 `docker compose down -v`로 DB를 초기화한다. Phase 1의 `count()` 비용은 누적 행 수에 비례한다.

**코드/주석 컨벤션 (Phase 0 스펙 4절)**

- 주석은 "왜"만. 정당한 경우는 셋뿐 — 의도적으로 취약한 코드, 트레이드오프의 이유, 직관에 반하는 동작.
- 금지: 형식적 Javadoc, 섹션 구분 주석, 장식용 구분선, 이모지, `// TODO`, 불필요한 방어 코드.
- 테스트 메서드명은 한국어 서술형. 들여쓰기는 탭(기존 파일과 동일).
- 커밋 메시지는 한국어, 제목 한 줄 + 이유가 자명하지 않을 때만 본문.
  끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

**셸** — Windows. Gradle은 `.\gradlew.bat`. PowerShell에서 `&&`, `||`, 삼항연산자 사용 불가.

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `src/test/java/com/example/coupon/support/MySqlTestBase.java` | 테스트 클래스 전체가 공유하는 MySQL 컨테이너 하나 | 1 |
| `src/main/java/com/example/coupon/domain/Coupon.java` | 수정 — 생성자, `getId()`, `getTotalQuantity()` | 1 |
| `src/main/java/com/example/coupon/domain/CouponIssue.java` | 수정 — 생성자 | 1 |
| `src/main/java/com/example/coupon/domain/CouponRepository.java` | 쿠폰 조회/저장 | 1 |
| `src/main/java/com/example/coupon/domain/CouponIssueRepository.java` | 발급 이력 저장, `countByCouponId` | 1 |
| `src/test/java/com/example/coupon/domain/CouponIssueRepositoryTest.java` | 파생 쿼리가 쿠폰별로 필터링하는지 | 1 |
| `src/test/java/com/example/coupon/CouponApplicationTests.java` | 수정 — 컨테이너를 베이스로 이전 | 1 |
| `src/main/java/com/example/coupon/issue/CouponIssueService.java` | 나이브 발급 로직. Phase 1의 취약 지점 | 2 |
| `src/test/java/com/example/coupon/issue/CouponIssueServiceTest.java` | 단일 스레드 정상 동작 (재고 있음 / 소진) | 2 |
| `src/main/java/com/example/coupon/issue/CouponIssueRequest.java` | 요청 본문 | 3 |
| `src/main/java/com/example/coupon/issue/CouponIssueController.java` | 상태코드 매핑만 | 3 |
| `src/test/java/com/example/coupon/issue/CouponIssueControllerTest.java` | 200 / 409 | 3 |
| `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTestBase.java` | 1000 스레드 시나리오 본문 | 4 |
| `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTest.java` | 풀 10 | 4 |
| `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyLargePoolTest.java` | 풀 50 | 4 |
| `load-test/phase1-issue.js` | k6 부하 스크립트 | 5 |
| `docs/benchmark.md` | 수정 — 조건 분리, 오버셀 표, 결과 행, 원인 설명 | 4, 5 |
| `docs/progress.md` | 수정 — Phase 1 체크, 실패 테스트 경고 | 5 |

**스펙 7절과 달라진 점.** 동시성 테스트를 `TestBase` + 구체 클래스 2개로 나눴다.
풀 10과 50은 시나리오가 완전히 같고 `@SpringBootTest` 속성만 다르다.
35줄을 복사하는 대신 상속으로 푼다.

**`MySqlTestBase`를 도입하는 이유.** Phase 0에서는 테스트 클래스가 1개여서
공용 베이스가 과한 구조라고 판단했다. Phase 1에서 6개가 되므로 판단이 바뀐다.
클래스마다 컨테이너를 띄우면 MySQL이 6번 뜬다.

---

### Task 1: 엔티티 보강, 리포지토리, 공용 테스트 베이스

**Files:**
- Create: `src/test/java/com/example/coupon/support/MySqlTestBase.java`
- Create: `src/main/java/com/example/coupon/domain/CouponRepository.java`
- Create: `src/main/java/com/example/coupon/domain/CouponIssueRepository.java`
- Modify: `src/main/java/com/example/coupon/domain/Coupon.java`
- Modify: `src/main/java/com/example/coupon/domain/CouponIssue.java`
- Modify: `src/test/java/com/example/coupon/CouponApplicationTests.java`
- Test: `src/test/java/com/example/coupon/domain/CouponIssueRepositoryTest.java`

**Interfaces:**
- Consumes: Phase 0의 `Coupon`, `CouponIssue` 엔티티와 `V1__init.sql` 스키마
- Produces:
  - `public abstract class MySqlTestBase` — 패키지 `com.example.coupon.support`. 상속만 하면 MySQL 접속이 잡힌다. `@Testcontainers`/`@Container`를 쓰지 않는다
  - `new Coupon(String name, int totalQuantity)`, `coupon.getId()` → `Long`, `coupon.getTotalQuantity()` → `int`
  - `new CouponIssue(Long couponId, Long userId, LocalDateTime issuedAt)`
  - `interface CouponRepository extends JpaRepository<Coupon, Long>`
  - `interface CouponIssueRepository extends JpaRepository<CouponIssue, Long>` with `long countByCouponId(Long couponId)`

- [ ] **Step 1: 공용 테스트 베이스 작성**

`src/test/java/com/example/coupon/support/MySqlTestBase.java`

```java
package com.example.coupon.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

public abstract class MySqlTestBase {

	// @Container를 쓰지 않고 직접 start()한다. JUnit의 Testcontainers 확장은 테스트 클래스마다
	// 컨테이너를 stop/start 하므로, 클래스가 여러 개면 MySQL도 그만큼 뜬다.
	// 정리는 Ryuk이 JVM 종료 시 맡는다.
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	static {
		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}
}
```

- [ ] **Step 2: 실패하는 리포지토리 테스트 작성**

`src/test/java/com/example/coupon/domain/CouponIssueRepositoryTest.java`

```java
package com.example.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.support.MySqlTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponIssueRepositoryTest extends MySqlTestBase {

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 발급_이력을_쿠폰별로_센다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 100장", 100));
		Coupon other = couponRepository.save(new Coupon("다른 쿠폰", 100));

		couponIssueRepository.save(new CouponIssue(coupon.getId(), 1L, LocalDateTime.now()));
		couponIssueRepository.save(new CouponIssue(coupon.getId(), 2L, LocalDateTime.now()));
		couponIssueRepository.save(new CouponIssue(other.getId(), 3L, LocalDateTime.now()));

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
	}
}
```

다른 쿠폰의 발급 이력을 하나 넣어두는 것이 핵심이다. 이것 없이는 파생 쿼리가
`coupon_id`로 필터링하는지 검증되지 않는다.

- [ ] **Step 3: 컴파일 실패 확인**

```powershell
.\gradlew.bat --no-daemon compileTestJava 2>&1 | Select-String -Pattern 'error:|cannot find symbol|BUILD' | Select-Object -First 15
```

기대: 컴파일 실패. `CouponRepository`, `CouponIssueRepository` 심볼을 찾을 수 없고
`Coupon`, `CouponIssue` 생성자가 없다.

- [ ] **Step 4: 엔티티에 생성자와 getter 추가**

`src/main/java/com/example/coupon/domain/Coupon.java` — `protected Coupon() {}` 뒤에 추가

```java
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
```

`getName()`은 만들지 않는다. 읽는 곳이 없다.

`src/main/java/com/example/coupon/domain/CouponIssue.java` — `protected CouponIssue() {}` 뒤에 추가

```java
	public CouponIssue(Long couponId, Long userId, LocalDateTime issuedAt) {
		this.couponId = couponId;
		this.userId = userId;
		this.issuedAt = issuedAt;
	}
```

`issuedAt`을 인자로 받는다. 엔티티가 직접 `LocalDateTime.now()`를 부르면
시간을 통제할 수 없다. getter는 읽는 곳이 없으므로 만들지 않는다.

- [ ] **Step 5: 리포지토리 2개 작성**

`src/main/java/com/example/coupon/domain/CouponRepository.java`

```java
package com.example.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
```

`src/main/java/com/example/coupon/domain/CouponIssueRepository.java`

```java
package com.example.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	long countByCouponId(Long couponId);
}
```

- [ ] **Step 6: 기존 테스트를 공용 베이스로 이전**

`src/test/java/com/example/coupon/CouponApplicationTests.java`에서
Testcontainers 관련 애노테이션과 필드를 지우고 `MySqlTestBase`를 상속한다.
컨테이너를 두 군데서 관리하면 베이스를 만든 이유가 없어진다.

지울 import:

```java
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
```

추가할 import:

```java
import com.example.coupon.support.MySqlTestBase;
```

클래스 선언과 컨테이너 필드를 아래로 교체:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponApplicationTests extends MySqlTestBase {

	@Autowired
	private JdbcTemplate jdbcTemplate;
```

즉 `@Testcontainers` 애노테이션과 `static MySQLContainer<?> mysql = ...` 필드 전체를 삭제한다.

- [ ] **Step 7: 테스트 통과 확인**

```powershell
.\gradlew.bat --no-daemon test 2>&1 | Select-String -Pattern 'FAILED|BUILD' | Select-Object -First 10
```

기대: `BUILD SUCCESSFUL`. 테스트 3개(기존 2개 + 신규 1개) 통과.

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml
```

기대: 두 파일 합쳐 `tests` 총 3, `failures` 0, `errors` 0.

- [ ] **Step 8: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/
git commit -m "$(printf 'feat: 리포지토리와 엔티티 생성자 추가\n\n테스트 클래스가 6개로 늘어날 예정이라 MySQL 컨테이너를 공용 베이스로\n뺐다. @Container는 클래스마다 stop/start 하므로 직접 start()하고\n정리는 Ryuk에 맡긴다.\n\nissuedAt을 생성자 인자로 받는다. 엔티티가 직접 now()를 부르면\n시간을 통제할 수 없다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 2: 나이브 발급 서비스

**Files:**
- Create: `src/main/java/com/example/coupon/issue/CouponIssueService.java`
- Test: `src/test/java/com/example/coupon/issue/CouponIssueServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `CouponRepository`, `CouponIssueRepository.countByCouponId(Long)`, `new Coupon(String, int)`, `coupon.getId()`, `coupon.getTotalQuantity()`, `new CouponIssue(Long, Long, LocalDateTime)`, `MySqlTestBase`
- Produces: `CouponIssueService.issue(Long couponId, Long userId)` → `boolean` (발급 성공 `true`, 품절 `false`). 패키지 `com.example.coupon.issue`. `@Transactional`. Task 3의 컨트롤러와 Task 4의 동시성 테스트가 이것을 호출한다

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/coupon/issue/CouponIssueServiceTest.java`

```java
package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponIssueServiceTest extends MySqlTestBase {

	@Autowired
	private CouponIssueService couponIssueService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 재고가_남아있으면_발급한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 2장", 2));

		boolean issued = couponIssueService.issue(coupon.getId(), 1L);

		assertThat(issued).isTrue();
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void 재고가_소진되면_발급하지_않는다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 2장", 2));
		couponIssueService.issue(coupon.getId(), 1L);
		couponIssueService.issue(coupon.getId(), 2L);

		boolean issued = couponIssueService.issue(coupon.getId(), 3L);

		assertThat(issued).isFalse();
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
	}
}
```

- [ ] **Step 2: 컴파일 실패 확인**

```powershell
.\gradlew.bat --no-daemon compileTestJava 2>&1 | Select-String -Pattern 'error:|cannot find symbol|BUILD' | Select-Object -First 10
```

기대: `CouponIssueService` 심볼을 찾을 수 없어 실패.

- [ ] **Step 3: 서비스 구현**

`src/main/java/com/example/coupon/issue/CouponIssueService.java`

```java
package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssue;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponIssueService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	public CouponIssueService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
	}

	// 락이 없다. count()로 읽은 발급 수는 save()가 커밋될 때까지 다른 트랜잭션에게
	// 보이지 않으므로, 동시에 들어온 요청들이 모두 같은 수를 읽고 모두 통과한다.
	// Phase 1은 이 문제를 재현하는 것이 목적이므로 고치지 않는다.
	@Transactional
	public boolean issue(Long couponId, Long userId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow();

		long issued = couponIssueRepository.countByCouponId(couponId);
		if (issued >= coupon.getTotalQuantity()) {
			return false;
		}

		couponIssueRepository.save(new CouponIssue(couponId, userId, LocalDateTime.now()));
		return true;
	}
}
```

`orElseThrow()`로 두고 없는 쿠폰은 500이 나가게 둔다. 테스트와 k6는 항상 존재하는
쿠폰 ID를 쓰므로 이 경로는 측정에 등장하지 않는다.

- [ ] **Step 4: 테스트 통과 확인**

```powershell
.\gradlew.bat --no-daemon test 2>&1 | Select-String -Pattern 'FAILED|BUILD' | Select-Object -First 10
```

기대: `BUILD SUCCESSFUL`. 테스트 5개 통과.

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/
git commit -m "$(printf 'feat: 나이브 쿠폰 발급 로직\n\ncount()로 발급 수를 세고 재고와 비교한 뒤 save()한다. 락은 없다.\n일부러 취약하게 만든 것이고 Phase 2에서 고친다.\n\n품절을 예외가 아니라 boolean으로 돌린다. 품절은 예외 상황이 아니라\n정상적인 결과 중 하나다. Phase 1에 예외 체계를 세우지 않는다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 3: 발급 API

**Files:**
- Create: `src/main/java/com/example/coupon/issue/CouponIssueRequest.java`
- Create: `src/main/java/com/example/coupon/issue/CouponIssueController.java`
- Test: `src/test/java/com/example/coupon/issue/CouponIssueControllerTest.java`

**Interfaces:**
- Consumes: Task 2의 `CouponIssueService.issue(Long, Long)` → `boolean`, Task 1의 `MySqlTestBase`, `CouponRepository`, `new Coupon(String, int)`
- Produces: `POST /coupons/{couponId}/issues`, 요청 본문 `{"userId": <number>}`, 응답 200(발급) 또는 409(품절), 본문 없음. Task 5의 k6 스크립트가 이 계약에 의존한다. `public record CouponIssueRequest(Long userId)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/coupon/issue/CouponIssueControllerTest.java`

```java
package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.MySqlTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponIssueControllerTest extends MySqlTestBase {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 발급에_성공하면_200을_반환한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 1장", 1));

		ResponseEntity<Void> response = restTemplate.postForEntity(
				"/coupons/{couponId}/issues", Map.of("userId", 1), Void.class, coupon.getId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void 품절이면_409를_반환한다() {
		Coupon coupon = couponRepository.save(new Coupon("선착순 1장", 1));
		restTemplate.postForEntity("/coupons/{couponId}/issues", Map.of("userId", 1), Void.class, coupon.getId());

		ResponseEntity<Void> response = restTemplate.postForEntity(
				"/coupons/{couponId}/issues", Map.of("userId", 2), Void.class, coupon.getId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}
}
```

- [ ] **Step 2: 컴파일 실패 확인**

```powershell
.\gradlew.bat --no-daemon compileTestJava 2>&1 | Select-String -Pattern 'error:|BUILD' | Select-Object -First 6
```

기대: 성공한다. 이 테스트는 컨트롤러 클래스를 직접 참조하지 않고 HTTP로만 부른다.

- [ ] **Step 3: 테스트 실행해 404 실패 확인**

```powershell
.\gradlew.bat --no-daemon test --tests '*CouponIssueControllerTest' 2>&1 | Select-String -Pattern 'FAILED|BUILD' | Select-Object -First 6
```

기대: 두 테스트 모두 FAILED. 엔드포인트가 없어 404가 돌아온다.

```bash
grep -o 'expected: 200 OK[^<]*' build/test-results/test/TEST-com.example.coupon.issue.CouponIssueControllerTest.xml | head -2
```

기대: 실제값이 `404 NOT_FOUND`임이 보인다.

- [ ] **Step 4: 요청 본문 record 작성**

`src/main/java/com/example/coupon/issue/CouponIssueRequest.java`

```java
package com.example.coupon.issue;

public record CouponIssueRequest(Long userId) {
}
```

컨트롤러 안에 중첩하지 않고 별도 파일로 둔다. Phase 4에서 Kafka 메시지 스키마와
나란히 놓고 볼 것이다.

- [ ] **Step 5: 컨트롤러 작성**

`src/main/java/com/example/coupon/issue/CouponIssueController.java`

```java
package com.example.coupon.issue;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponIssueController {

	private final CouponIssueService couponIssueService;

	public CouponIssueController(CouponIssueService couponIssueService) {
		this.couponIssueService = couponIssueService;
	}

	@PostMapping("/coupons/{couponId}/issues")
	public ResponseEntity<Void> issue(@PathVariable Long couponId, @RequestBody CouponIssueRequest request) {
		// k6가 발급과 품절을 상태코드로 구분해야 한다. 둘 다 200이면 실제 발급률을 알 수 없다.
		if (couponIssueService.issue(couponId, request.userId())) {
			return ResponseEntity.ok().build();
		}
		return ResponseEntity.status(HttpStatus.CONFLICT).build();
	}
}
```

- [ ] **Step 6: 테스트 통과 확인**

```powershell
.\gradlew.bat --no-daemon test 2>&1 | Select-String -Pattern 'FAILED|BUILD' | Select-Object -First 10
```

기대: `BUILD SUCCESSFUL`. 테스트 7개 통과.

- [ ] **Step 7: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/
git commit -m "$(printf 'feat: 쿠폰 발급 API\n\n품절을 409로 돌린다. k6가 발급과 품절을 상태코드로 구분해야\n부하 테스트에서 실제 발급률을 알 수 있다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 4: 오버셀 재현 — Phase 1의 핵심

이 태스크의 산출물은 **통과하는 테스트가 아니라 실패한 수치**다.
테스트는 실패하는 상태로 커밋한다.

**Files:**
- Create: `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTestBase.java`
- Create: `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTest.java`
- Create: `src/test/java/com/example/coupon/issue/CouponIssueConcurrencyLargePoolTest.java`
- Modify: `docs/benchmark.md`

**Interfaces:**
- Consumes: Task 2의 `CouponIssueService.issue(Long, Long)`, Task 1의 `MySqlTestBase`, `CouponRepository`, `CouponIssueRepository.countByCouponId(Long)`
- Produces: 풀 10과 풀 50에서의 실제 발급 건수 2개. Task 5가 이 수치를 `docs/benchmark.md`의 결과와 함께 읽는다

- [ ] **Step 1: 시나리오 베이스 작성**

`src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTestBase.java`

```java
package com.example.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponIssueRepository;
import com.example.coupon.domain.CouponRepository;
import com.example.coupon.support.MySqlTestBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

abstract class CouponIssueConcurrencyTestBase extends MySqlTestBase {

	private static final int TOTAL_QUANTITY = 100;
	private static final int THREAD_COUNT = 1000;

	@Autowired
	private CouponIssueService couponIssueService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void 초기화() {
		couponIssueRepository.deleteAll();
		couponRepository.deleteAll();
	}

	@Test
	void 동시에_1000명이_요청해도_100장만_발급된다() throws InterruptedException {
		Coupon coupon = couponRepository.save(new Coupon("선착순 100장", TOTAL_QUANTITY));
		AtomicInteger exceptions = new AtomicInteger();

		// 스레드 풀을 작게 잡으면 스레드 풀이 병목이 되어 DB 경합을 관찰할 수 없다.
		// 실제 상한은 HikariCP 풀 크기이고, 그게 이 테스트의 관찰 대상이다.
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

		for (int i = 0; i < THREAD_COUNT; i++) {
			long userId = i;
			executor.submit(() -> {
				try {
					couponIssueService.issue(coupon.getId(), userId);
				} catch (Exception e) {
					// 예외를 세지 않고 삼키면 DB 오류를 오버셀로 착각한다.
					exceptions.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await();
		executor.shutdown();

		long issued = couponIssueRepository.countByCouponId(coupon.getId());
		System.out.printf("[%s] 발급=%d, 초과=%d, 예외=%d%n",
				getClass().getSimpleName(), issued, issued - TOTAL_QUANTITY, exceptions.get());

		assertThat(issued).isEqualTo(TOTAL_QUANTITY);
	}
}
```

- [ ] **Step 2: 구체 테스트 클래스 2개 작성**

`src/test/java/com/example/coupon/issue/CouponIssueConcurrencyTest.java`

```java
package com.example.coupon.issue;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponIssueConcurrencyTest extends CouponIssueConcurrencyTestBase {
}
```

`src/test/java/com/example/coupon/issue/CouponIssueConcurrencyLargePoolTest.java`

```java
package com.example.coupon.issue;

import org.springframework.boot.test.context.SpringBootTest;

// 오버셀 규모의 상한은 동시에 열릴 수 있는 트랜잭션 수, 즉 커넥션 풀 크기가 정한다.
// 기본 풀(10)과 비교해 그 관계를 수치로 남기려고 만든 클래스다.
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class CouponIssueConcurrencyLargePoolTest extends CouponIssueConcurrencyTestBase {
}
```

- [ ] **Step 3: 두 테스트 실행 — 실패와 실제 수치 확인**

```powershell
.\gradlew.bat --no-daemon test --tests '*CouponIssueConcurrency*' 2>&1 | Select-String -Pattern 'FAILED|BUILD|tests completed' | Select-Object -First 10
```

기대: 두 테스트 모두 FAILED. 이것이 Phase 1의 정상 상태다.

실제 수치를 읽는다.

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
grep -oE '\[CouponIssueConcurrency[A-Za-z]*Test\] 발급=[0-9]+, 초과=-?[0-9]+, 예외=[0-9]+' build/test-results/test/TEST-com.example.coupon.issue.CouponIssueConcurrency*.xml
grep -oE 'expected: 100L?[^<]{0,40}' build/test-results/test/TEST-com.example.coupon.issue.CouponIssueConcurrency*.xml
```

기대: 두 클래스 모두 발급 건수가 100을 초과한다. 예외는 0이어야 한다.
예외가 0이 아니면 오버셀이 아니라 DB 오류를 보고 있는 것이므로 원인을 먼저 찾는다.

`OutOfMemoryError: unable to create native thread`가 나면 플랫폼 스레드 1000개가
로컬에서 무거운 것이다. `Executors.newFixedThreadPool(THREAD_COUNT)`를
`Executors.newVirtualThreadPerTaskExecutor()`로 바꾸고 그 사실을
`docs/benchmark.md`의 정합성 조건 표에 기록한다.

**발급 건수가 정확히 100이면** 오버셀이 재현되지 않은 것이다.
`docs/benchmark.md`에 그 사실을 기록하고, 스펙 9절의 대응대로 풀 크기를 더 올려
재시도한 뒤 결과를 남긴다. 임의로 조건을 바꾸고 조용히 넘어가지 않는다.

- [ ] **Step 4: `docs/benchmark.md`의 고정 조건을 목적별로 분리**

`## 고정 조건` 섹션 전체를 아래로 교체한다. 기존 표는 쿠폰 수량 100과 VU 200을
한 표에 두고 있어, 그대로 측정하면 첫 수십 밀리초에 전량 소진되고 남은 30초가
거절 경로 측정이 된다.

```markdown
## 측정 조건

목적이 다르므로 조건도 다르다. 두 조건의 수치를 섞어 비교하지 않는다.

### 정합성 (JUnit + Testcontainers)

| 항목 | 값 |
|---|---|
| 쿠폰 총 수량 | 100 |
| 요청 스레드 수 | 1000 (`ExecutorService` + `CountDownLatch`) |
| 측정 대상 | 최종 발급 건수 |
| 통과 기준 | 정확히 100건 |

### 부하 (k6)

| 항목 | 값 |
|---|---|
| 쿠폰 총 수량 | 1,000,000 (30초 안에 소진되지 않도록) |
| VU | 200 |
| duration | 30s |
| 측정 대상 | TPS, p95, p99 |
| 사전 조건 | `docker compose down -v`로 DB 초기화 |

수량을 100으로 두고 부하를 걸면 첫 수십 밀리초에 전량이 소진되고 남은 시간은
전부 품절 응답이다. 그러면 TPS와 p99가 발급 경로가 아니라 거절 경로를 재게 되어
Phase 간 비교가 무의미해진다.

`count()` 비용은 누적 행 수에 비례하므로 매 측정 전 DB를 비운다.

### 공통

| 항목 | 값 |
|---|---|
| HikariCP `maximum-pool-size` | 10 |
| `innodb_flush_log_at_trx_commit` | 1 (기본값) |
| `innodb_buffer_pool_size` | 128MB (기본값) |
| `innodb_lock_wait_timeout` | 50 (기본값, Phase 2에서 조정 예정) |
| 기본 격리 수준 | REPEATABLE READ |

`innodb_flush_log_at_trx_commit`을 2로 바꾸면 TPS가 크게 오르지만
그건 개선이 아니라 내구성을 팔아 얻은 수치다. 기본값을 지킨다.
```

- [ ] **Step 5: 오버셀 측정 결과를 `docs/benchmark.md`에 기록**

`## 결과` 섹션 바로 위에 아래 섹션을 추가한다.
`<풀10>`, `<풀50>` 자리에는 Step 3에서 읽은 실제 발급 건수를 넣는다.

```markdown
## 오버셀 (정합성 조건)

쿠폰 100장에 1000 스레드가 동시 요청한 결과다.

| 커넥션 풀 크기 | 발급 건수 | 초과 | 테스트 |
|---|---|---|---|
| 10 | <풀10> | <풀10 - 100> | `CouponIssueConcurrencyTest` |
| 50 | <풀50> | <풀50 - 100> | `CouponIssueConcurrencyLargePoolTest` |

**오버셀 규모의 상한은 커넥션 풀 크기가 정한다.**
`count()`와 `save()` 커밋 사이의 창에서 겹칠 수 있는 트랜잭션 수가
풀 크기로 제한되기 때문이다. 1000 스레드를 던져도 DB에서 실제로 경합하는 것은
풀 크기만큼이다. 풀을 10에서 50으로 올렸을 때 초과분이 어떻게 변했는지가 위 표다.

### 원인 — read-modify-write

1. **read.** 트랜잭션 A와 B가 각각 `countByCouponId()`로 발급 수를 읽는다.
   MySQL 기본 격리 수준 REPEATABLE READ에서 각 트랜잭션은 첫 읽기 시점의
   스냅샷을 보므로, 거의 동시에 시작한 A와 B는 **같은 값(예: 99)** 을 읽는다.
2. **modify.** 둘 다 `99 >= 100`이 거짓이므로 발급 조건을 통과한다.
   상대방이 곧 발급할 것이라는 정보가 어디에도 없다.
3. **write.** 둘 다 insert하고 커밋한다. 발급 수는 101이 된다.

읽은 값이 쓰기 시점까지 유효하다는 보장이 없는데 그 값을 근거로 쓰기를 결정한 것이
문제다. 읽기와 쓰기가 하나의 원자적 단위가 아니다.
```

- [ ] **Step 6: 커밋**

실패하는 테스트를 커밋한다. 의도된 상태다.

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/ docs/benchmark.md
git commit -m "$(printf 'test: 오버셀 재현 - 1000 스레드 동시 요청\n\n이 테스트는 실패하는 상태로 커밋한다. Phase 1의 목적이 정합성 확보가\n아니라 문제 재현이기 때문이다. Phase 2에서 통과로 바뀐다.\n\n오버셀 규모의 상한이 커넥션 풀 크기라는 것을 풀 10과 50으로 각각\n측정해 확인했다. count()와 save() 사이에서 겹칠 수 있는 트랜잭션 수가\n풀 크기로 제한되기 때문이다.\n\n벤치마크 고정 조건을 정합성과 부하로 분리했다. 수량 100에 VU 200을\n30초 걸면 남은 시간이 전부 거절 경로 측정이 된다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 5: k6 부하 측정, 문서, push

**Files:**
- Create: `load-test/phase1-issue.js`
- Modify: `docs/benchmark.md`
- Modify: `docs/progress.md`

**Interfaces:**
- Consumes: Task 3의 `POST /coupons/{couponId}/issues` 계약(200/409), Task 4가 갱신한 `docs/benchmark.md` 구조
- Produces: Phase 1의 TPS / p95 / p99. Phase 2가 같은 조건으로 측정해 비교할 기준선

- [ ] **Step 1: k6 스크립트 작성**

`load-test/phase1-issue.js`

```javascript
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const issued = new Counter('coupon_issued');
const soldOut = new Counter('coupon_sold_out');
const failed = new Counter('coupon_failed');

export const options = {
	vus: 200,
	duration: '30s',
};

const couponId = __ENV.COUPON_ID;
const url = `http://localhost:8080/coupons/${couponId}/issues`;

export default function () {
	// VU 번호와 반복 횟수를 곱해 흩어놓는다. 문자열 연결로 만들면
	// VU 1/반복 12와 VU 11/반복 2가 같은 값이 된다.
	const userId = __VU * 1000000 + __ITER;

	const res = http.post(url, JSON.stringify({ userId }), {
		headers: { 'Content-Type': 'application/json' },
	});

	if (res.status === 200) {
		issued.add(1);
	} else if (res.status === 409) {
		soldOut.add(1);
	} else {
		failed.add(1);
	}
}
```

`thresholds`를 두지 않는다. 측정이 목적이고 통과/실패 판정이 목적이 아니다.

- [ ] **Step 2: DB 초기화 후 부하 테스트용 쿠폰 생성**

```powershell
docker compose down -v; if ($?) { docker compose up -d }
```

MySQL이 healthy가 될 때까지 기다린다.

```bash
until [ "$(docker inspect -f '{{.State.Health.Status}}' coupon-mysql 2>/dev/null)" = "healthy" ]; do sleep 3; done; echo healthy
```

앱을 띄워 Flyway로 스키마를 만든 뒤 쿠폰을 넣는다. 스키마가 없으면 insert가 실패한다.

```powershell
.\gradlew.bat bootRun
```

`Started CouponApplication`을 확인한 뒤 다른 셸에서:

```powershell
docker exec coupon-mysql mysql -ucoupon -pcoupon coupon -e "insert into coupon (name, total_quantity) values ('부하 테스트용', 1000000); select id, total_quantity from coupon;"
```

출력된 `id`를 다음 단계의 `COUPON_ID`로 쓴다.

- [ ] **Step 3: k6 실행**

```powershell
$machine = [Environment]::GetEnvironmentVariable('Path','Machine'); $user = [Environment]::GetEnvironmentVariable('Path','User'); $env:Path = "$machine;$user"
k6 run -e COUPON_ID=1 load-test/phase1-issue.js
```

`COUPON_ID`는 Step 2에서 확인한 실제 값으로 바꾼다.

출력에서 아래를 읽는다.

- `http_reqs` 의 `rate` → TPS
- `http_req_duration` 의 `p(95)`, `p(99)`
- `coupon_issued`, `coupon_sold_out`, `coupon_failed` 카운트

**`coupon_sold_out`이 0이어야 한다.** 0이 아니면 30초 안에 100만 건이 나갔다는
뜻이고, 조건을 다시 잡아 재측정해야 한다.
`coupon_failed`가 0이 아니면 500 등 다른 오류가 섞인 것이므로 원인을 먼저 찾는다.

앱을 종료한다.

- [ ] **Step 4: `docs/benchmark.md` 결과 표에 Phase 1 행 추가**

`## 결과` 섹션의 표에 행을 추가하고, 그 아래 문단을 교체한다.
`<TPS>`, `<p95>`, `<p99>`, `<오버셀>` 자리에는 실측값을 넣는다.
`<오버셀>`은 Task 4의 풀 10 결과에서 100을 뺀 값이다.

```markdown
| Phase | 방식 | TPS | p95 | p99 | 오버셀 | 비고 |
|-------|------|-----|-----|-----|--------|------|
| 1 | 나이브 (`count()` → 비교 → `save()`, 락 없음) | <TPS> | <p95> | <p99> | <오버셀>건 | 오버셀은 정합성 조건(수량 100, 1000 스레드, 풀 10)에서 측정. TPS/p95/p99는 부하 조건(수량 100만, VU 200, 30s) |

오버셀 수치와 지연 수치는 조건이 다르다. 같은 행에 있지만 같은 실행의 결과가 아니다.
정합성은 JUnit + Testcontainers, 부하는 k6 + compose MySQL에서 측정했다.

부하 테스트 중 발급 행이 계속 쌓이므로 `countByCouponId()`가 훑는 행 수가
30초 동안 증가한다. 나이브 구현의 지연은 시간이 갈수록 나빠지는 성질이 있고,
이 수치는 그 평균이다. Phase 3에서 Redis로 옮기면 이 성질 자체가 사라진다.
```

- [ ] **Step 5: `docs/progress.md` 갱신**

`현재 단계` 줄을 바꾼다.

```markdown
현재 단계: **Phase 2 — DB 락으로 해결 (비교군)**
```

Phase 1 섹션의 체크박스를 채우고, 시작 전 준비물 문단을 아래로 교체한다.

```markdown
## Phase 1 — 나이브 구현 + 실패 재현

- [x] `count()` 기반 무락 발급 로직
- [x] 1000 스레드 동시성 테스트에서 오버셀 재현 (`ExecutorService` + `CountDownLatch`)
- [x] 실제 발급 건수를 `docs/benchmark.md`에 기록
- [x] k6 부하 테스트로 TPS, p95, p99 측정

완료 조건: 오버셀이 실제로 발생하고 그 수치가 문서에 남아 있을 것. 충족.

**`CouponIssueConcurrencyTest`와 `CouponIssueConcurrencyLargePoolTest`는
실패하는 것이 정상이다.** Phase 1의 산출물이 통과하는 테스트가 아니라
실패한 수치이기 때문이다. Phase 2에서 통과로 바뀐다.
`gradlew test`는 Phase 2 완료 전까지 빨간불이다.
```

- [ ] **Step 6: 전체 테스트 상태 확인**

```powershell
.\gradlew.bat --no-daemon test 2>&1 | Select-String -Pattern 'FAILED|BUILD|tests completed' | Select-Object -First 12
```

기대: `BUILD FAILED`. 동시성 테스트 2개만 실패하고 나머지 7개는 통과한다.
다른 테스트가 실패했다면 그건 의도된 상태가 아니므로 고친다.

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml
```

기대: 실패 총 2건이고 둘 다 `CouponIssueConcurrency`로 시작하는 클래스다.

- [ ] **Step 7: 커밋 및 push**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add load-test/ docs/benchmark.md docs/progress.md
git commit -m "$(printf 'perf: Phase 1 k6 부하 측정 기준선\n\n수량 100만 쿠폰에 VU 200을 30초 걸어 TPS와 p95/p99를 기록했다.\n품절 응답이 0건이므로 모든 요청이 실제 발급 경로를 탔다.\n\nPhase 2 이후 이 수치와 비교한다. 오버셀 수치와 지연 수치는\n조건이 다르므로 표에 그 사실을 명시했다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
git push origin main
```

---

## Phase 1 완료 조건 (스펙 8절)

- [ ] `CouponIssueConcurrencyTest`가 실패하고 실제 발급 건수가 100을 초과한다
- [ ] 풀 50 테스트도 실패하고 오버셀 규모가 풀 10과 비교 가능하게 기록된다
- [ ] 두 수치가 `docs/benchmark.md`에 있다
- [ ] `CouponApplicationTests`, `CouponIssueRepositoryTest`, `CouponIssueServiceTest`, `CouponIssueControllerTest` 총 7개는 통과한다
- [ ] k6 실행이 완료되고 `coupon_sold_out`이 0건이다
- [ ] TPS / p95 / p99가 `docs/benchmark.md` 결과 표에 있다
- [ ] read-modify-write 관점의 원인 설명이 문서에 있다
- [ ] `origin/main`에 push 완료
