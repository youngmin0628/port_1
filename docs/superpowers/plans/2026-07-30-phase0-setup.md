# Phase 0 셋업 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 1에서 오버셀을 재현할 수 있는 최소한의 Spring Boot + MySQL 무대를 만든다. 발급 로직은 만들지 않는다.

**Architecture:** Gradle 단일 모듈 Spring Boot 앱이 로컬에서 돌고, MySQL만 docker-compose로 띄운다. 스키마는 Flyway가 관리하고 `ddl-auto: validate`가 엔티티 매핑과의 일치를 부팅 시 검증한다. Redis/Kafka는 compose에 정의만 해두고 profile로 격리해 Phase 3/4까지 기동하지 않는다.

**Tech Stack:** Java 21 (Gradle toolchain) / Spring Boot 3.5.x / Gradle 8.14+ / JPA / Flyway / MySQL 8.4 / Testcontainers / Docker Compose

## Global Constraints

모든 태스크의 요구사항에 아래가 암묵적으로 포함된다. 스펙 문서: `docs/superpowers/specs/2026-07-30-phase0-setup-design.md`

- **Redis / Kafka 의존성과 코드를 넣지 않는다.** compose에 컨테이너만 정의하고 앱은 이들의 존재를 모른다.
- **`(coupon_id, user_id)` unique 제약을 넣지 않는다.** Phase 2(c)의 비교군 재료다.
- **리포지토리, 서비스, 컨트롤러를 만들지 않는다.** Phase 1의 작업이다.
- **전역 예외 핸들러, 커스텀 예외, 요청 검증, 인터페이스 추상화를 만들지 않는다.**
- **Dockerfile과 k8s 매니페스트를 만들지 않는다.**
- 패키지: `com.example.coupon` / 그룹: `com.example` / 아티팩트: `coupon`
- Java toolchain: **21** (로컬 JDK는 24다. toolchain으로 21을 고정한다)
- MySQL 이미지: **`mysql:8.4`** — compose와 Testcontainers가 같은 태그를 쓴다
- HikariCP `maximum-pool-size`: **10** (`DB_POOL_SIZE` 환경변수로 조절)
- `spring.jpa.hibernate.ddl-auto`: **`validate`** (`update` 금지 — 벤치마크 재현성을 깬다)
- MySQL 성능 파라미터(`innodb_flush_log_at_trx_commit`, `innodb_buffer_pool_size`)를 건드리지 않는다
- JDBC URL에 성능 튜닝 파라미터를 붙이지 않는다
- 브랜치 `main`, 원격 `origin` = `https://github.com/youngmin0628/port_1.git`

**코드/주석 컨벤션 (스펙 4절)** — 매 태스크에서 지킨다.

- 주석은 "왜"만 쓴다. 코드가 무엇을 하는지 번역하는 주석을 쓰지 않는다.
- 주석이 정당한 경우는 셋뿐이다: 의도적으로 취약한 코드, 트레이드오프의 이유, 직관에 반하는 동작.
- 금지: 형식적 Javadoc, 섹션 구분 주석(`// ===== 필드 =====`), 장식용 구분선, 이모지, 체크마크, `// TODO`, `// FIXME`, 발생 불가능한 시나리오의 null 체크/try-catch.
- 이름은 도메인 용어로 짧게. `DTO` 접미사는 실제로 계층 경계를 넘는 타입에만.
- 커밋 메시지는 한국어. 제목 한 줄, 본문은 이유가 자명하지 않을 때만. 불릿 기계적 나열 금지.
- 테스트 메서드명은 한국어 서술형(`마이그레이션이_적용되고_엔티티_매핑이_스키마와_일치한다`).
- 커밋 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 트레일러를 유지한다.

**셸 주의사항** — Windows 환경이다.

- Gradle은 `.\gradlew.bat`으로 실행한다 (zip에서 풀린 `gradlew`에 실행 권한이 없을 수 있다).
- PowerShell에서 `&&`, `||`, 삼항연산자를 쓸 수 없다. `A; if ($?) { B }`를 쓴다.

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `settings.gradle` | 루트 프로젝트명, foojay toolchain resolver | 1 |
| `build.gradle` | 플러그인, toolchain 21, 의존성 | 1 |
| `gradlew.bat`, `gradlew`, `gradle/wrapper/*` | Gradle 버전 고정 | 1 |
| `.gitignore` | 빌드 산출물 제외 | 1 |
| `src/main/java/com/example/coupon/CouponApplication.java` | 부트 진입점 | 1 |
| `docker-compose.yml` | 로컬 인프라. MySQL 기본, Redis/Kafka는 profile | 2 |
| `src/main/resources/application.yml` | 접속 정보(env 외부화), JPA/Flyway/Actuator 설정 | 3 |
| `src/main/java/com/example/coupon/domain/Coupon.java` | 쿠폰 엔티티 | 3 |
| `src/main/java/com/example/coupon/domain/CouponIssue.java` | 발급 이력 엔티티 | 3 |
| `src/main/resources/db/migration/V1__init.sql` | 초기 스키마 | 3 |
| `src/test/java/com/example/coupon/CouponApplicationTests.java` | Testcontainers 위에서 스키마 정합성 + 헬스체크 검증 | 3, 4 |
| `docs/progress.md` | 현재 Phase와 완료 조건 체크리스트 | 5 |
| `docs/benchmark.md` | 측정 환경, 고정 조건, 결과 표 | 5 |

테스트 클래스는 **하나만** 만든다. 클래스마다 static 컨테이너를 두면 MySQL 컨테이너가 여러 번 뜨고, 그걸 피하려고 공용 베이스 클래스를 만드는 것은 테스트 2개짜리 Phase 0에서 과한 구조다.

---

### Task 1: Gradle 프로젝트 부트스트랩

로컬에 Gradle이 전역 설치되어 있지 않아 `gradle wrapper`를 실행할 수 없다. `start.spring.io`에서 프로젝트를 받아 wrapper를 확보한다.

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `.gitignore`
- Create: `src/main/java/com/example/coupon/CouponApplication.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `.\gradlew.bat` 실행 가능한 Gradle 프로젝트. 패키지 `com.example.coupon`. 클래스 `com.example.coupon.CouponApplication`. Task 3~4의 테스트가 쓸 의존성 — `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `flyway-core`, `flyway-mysql`, `mysql-connector-j`, `spring-boot-starter-test`, `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, `org.testcontainers:mysql`

- [ ] **Step 1: Spring Boot 3.5 계열 최신 버전 확인**

Initializr의 기본 bootVersion은 4.x다. CLAUDE.md 스택은 3.x이므로 명시해야 한다.

```bash
curl -s https://start.spring.io/metadata/client | python -c "import sys,json; d=json.load(sys.stdin); print([v['id'] for v in d['bootVersion']['values']])"
```

출력에서 `3.5.`로 시작하는 것 중 가장 높은 버전을 고른다. 이 값을 이후 단계에서 `<BOOT>`로 쓴다.

python이 없으면:

```bash
curl -s https://start.spring.io/metadata/client | grep -o '"id":"3\.5\.[0-9]*"' | sort -V | tail -1
```

- [ ] **Step 2: 스크래치패드에 프로젝트 생성**

기존 `CLAUDE.md`, `PROMPTS.md`, `docs/`, `.git/`를 덮어쓰지 않기 위해 스크래치패드에 풀고 필요한 것만 복사한다.

```bash
SCRATCH="C:/Users/song/AppData/Local/Temp/claude/C--Users-song-Desktop-portfolioPjt/fa878ccf-5031-4c60-9f5d-09d03f38a287/scratchpad"
mkdir -p "$SCRATCH/init"
curl -s -o "$SCRATCH/starter.zip" "https://start.spring.io/starter.zip?type=gradle-project&language=java&bootVersion=<BOOT>&groupId=com.example&artifactId=coupon&name=coupon&packageName=com.example.coupon&javaVersion=21&dependencies=web,data-jpa,actuator,flyway,mysql,testcontainers"
unzip -o -q "$SCRATCH/starter.zip" -d "$SCRATCH/init"
ls -la "$SCRATCH/init"
```

기대: `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/`, `src/`, `.gitignore`, `HELP.md`가 보인다.

`start.spring.io`가 응답하지 않으면 Gradle 배포 zip으로 우회한다. `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`을 스크래치패드에 풀고, 그 안의 `bin/gradle`로 프로젝트 루트에서 `gradle wrapper --gradle-version 8.14.3`을 한 번 실행해 wrapper를 만든 뒤 `build.gradle`과 `settings.gradle`을 손으로 작성한다(내용은 Step 4~5에 있다).

- [ ] **Step 3: 필요한 파일만 프로젝트 루트로 복사**

`HELP.md`, Initializr의 `application.properties`(우리는 yml을 쓴다), 기본 테스트 클래스(Task 3에서 직접 쓴다)는 가져오지 않는다.

```bash
SCRATCH="C:/Users/song/AppData/Local/Temp/claude/C--Users-song-Desktop-portfolioPjt/fa878ccf-5031-4c60-9f5d-09d03f38a287/scratchpad"
DEST="C:/Users/song/Desktop/portfolioPjt"
cp -r "$SCRATCH/init/gradle" "$DEST/"
cp "$SCRATCH/init/gradlew" "$SCRATCH/init/gradlew.bat" "$SCRATCH/init/build.gradle" "$SCRATCH/init/settings.gradle" "$SCRATCH/init/.gitignore" "$DEST/"
mkdir -p "$DEST/src/main/java/com/example/coupon" "$DEST/src/main/resources" "$DEST/src/test/java/com/example/coupon"
cp "$SCRATCH/init/src/main/java/com/example/coupon/CouponApplication.java" "$DEST/src/main/java/com/example/coupon/"
find "$DEST/src" -type f
cat "$DEST/build.gradle"
cat "$DEST/settings.gradle"
cat "$DEST/gradle/wrapper/gradle-wrapper.properties"
```

기대: `src` 아래에 `CouponApplication.java` 하나만. `gradle-wrapper.properties`의 `distributionUrl`이 Gradle 8.14 이상.

- [ ] **Step 4: `settings.gradle`에 foojay toolchain resolver 추가**

`plugins` 블록은 `settings.gradle`의 첫 블록이어야 한다.

```gradle
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.10.0'
}

rootProject.name = 'coupon'
```

- [ ] **Step 5: `build.gradle` 확인 및 정리**

Initializr 결과에 아래가 모두 있어야 한다. 없으면 추가한다.

```gradle
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'org.flywaydb:flyway-mysql'
    runtimeOnly 'com.mysql:mysql-connector-j'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:mysql'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

Initializr가 `spring-boot-docker-compose`를 넣었다면 **제거한다.** 앱이 compose 라이프사이클을 자동으로 건드리면 벤치마크 중 컨테이너 상태를 우리가 통제할 수 없다.

이어서 `.gitignore`를 확인한다. Initializr 기본값은 `build/`, `.gradle`, `.idea`, `out/`를 덮지만 `*.log`는 빠져 있다. 없으면 끝에 한 줄 추가한다.

```
*.log
```

`gradle/wrapper/gradle-wrapper.jar`가 무시 대상에 들어가 있지 않은지도 확인한다. wrapper jar는 커밋해야 한다.

- [ ] **Step 6: 빌드 확인 — toolchain이 실제로 21을 쓰는지**

```powershell
.\gradlew.bat --no-daemon compileJava -q; if ($?) { .\gradlew.bat --no-daemon -q javaToolchains }
```

기대: 컴파일 성공. `javaToolchains` 출력에 21 항목이 있고, 로컬에 21이 없었다면 Gradle이 내려받는 로그가 보인다.

여기서 foojay 플러그인 버전 해석이 실패하면(`Plugin ... not found`) `0.10.0`을 최신으로 올린다. 확인:

```bash
curl -s "https://plugins.gradle.org/api/gradle/plugin/use/org.gradle.toolchains.foojay-resolver-convention" | grep -o '"version":"[^"]*"' | head -1
```

- [ ] **Step 7: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add -A
git commit -m "$(printf 'chore: Gradle 프로젝트 뼈대 생성\n\n로컬에 Gradle이 없어 start.spring.io에서 wrapper를 받아왔다.\ntoolchain을 21로 고정한 이유는 로컬 JDK가 24라서다 -\n측정이 목적인 프로젝트에서 런타임이 Phase마다 달라지면 안 된다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 2: docker-compose 인프라

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: 없음
- Produces: `localhost:3306`의 MySQL 8.4. DB `coupon`, 유저 `coupon`, 비밀번호 `coupon`. Task 3의 `application.yml` 기본값이 이 값에 맞춰진다. Redis(`localhost:6379`)와 Kafka(`localhost:9092`)는 profile 뒤에 숨어 있다.

- [ ] **Step 1: Docker Desktop 기동 확인**

확인 시점에 꺼져 있었다.

```powershell
docker info --format '{{.ServerVersion}}'
```

실패하면 Docker Desktop을 실행하고 데몬이 올라올 때까지 기다린 뒤 다시 확인한다.

- [ ] **Step 2: `docker-compose.yml` 작성**

```yaml
services:
  mysql:
    image: mysql:8.4
    container_name: coupon-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: coupon
      MYSQL_USER: coupon
      MYSQL_PASSWORD: coupon
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-ucoupon", "-pcoupon"]
      interval: 5s
      timeout: 3s
      retries: 20
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    image: redis:7.4-alpine
    container_name: coupon-redis
    profiles:
      - redis
    ports:
      - "6379:6379"

  kafka:
    image: apache/kafka:3.9.0
    container_name: coupon-kafka
    profiles:
      - kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1

volumes:
  mysql-data:
```

성능 파라미터는 `command`에 넣지 않는다. `innodb_flush_log_at_trx_commit`과 `innodb_buffer_pool_size`는 기본값을 지키고 벤치마크 조건에 기록한다.

- [ ] **Step 3: 기본 기동 — MySQL만 뜨는지 확인**

```powershell
docker compose up -d; if ($?) { docker compose ps --format 'table {{.Service}}\t{{.Status}}' }
```

기대: `mysql`만 나오고 `(healthy)` 상태. `redis`, `kafka`는 목록에 없다. healthy까지 30초 정도 걸릴 수 있으니 아직 `starting`이면 다시 확인한다.

- [ ] **Step 4: 접속 확인**

```powershell
docker exec coupon-mysql mysql -ucoupon -pcoupon -e "select @@version, @@transaction_isolation, @@innodb_flush_log_at_trx_commit, @@innodb_lock_wait_timeout;"
```

기대: 버전 8.4.x, `REPEATABLE-READ`, `1`, `50`. 이 값들이 Phase 1~2에서 관찰 대상이 되는 기본값이다.

- [ ] **Step 5: profile 스모크 체크**

Phase 3/4에서 처음 삽을 파지 않기 위해 YAML을 지금 검증한다.

```powershell
docker compose --profile redis --profile kafka up -d; if ($?) { docker compose --profile redis --profile kafka ps --format 'table {{.Service}}\t{{.Status}}' }
```

기대: `mysql`, `redis`, `kafka` 세 개 모두 `Up`. Kafka가 `Restarting`이면 로그를 확인한다.

```powershell
docker compose logs kafka --tail 40
```

- [ ] **Step 6: Redis/Kafka 내리고 MySQL만 남기기**

`down`은 볼륨을 지우지 않으므로 MySQL 데이터는 남는다. 지우려면 `-v`가 필요하다.

```powershell
docker compose --profile redis --profile kafka down; if ($?) { docker compose up -d }
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
```

기대: `mysql`만 healthy로 남는다.

- [ ] **Step 7: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add docker-compose.yml
git commit -m "$(printf 'chore: 로컬 인프라 docker-compose 구성\n\nRedis와 Kafka는 Phase 3, 4까지 쓰지 않으므로 profile로 격리했다.\nPhase 1~2 벤치마크가 유휴 Kafka 브로커와 CPU를 나눠 쓰지 않게 하려는 것이다.\nMySQL 성능 파라미터는 기본값을 지킨다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 3: 엔티티 + 마이그레이션 (TDD 핵심)

이 태스크가 Phase 0의 실질적인 검증이다. 엔티티만 있고 마이그레이션이 없는 상태에서 테스트가 `Schema-validation: missing table [coupon]`으로 실패하는 것을 먼저 확인한 뒤, 마이그레이션을 넣어 통과시킨다.

**Files:**
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/example/coupon/domain/Coupon.java`
- Create: `src/main/java/com/example/coupon/domain/CouponIssue.java`
- Create: `src/main/resources/db/migration/V1__init.sql`
- Test: `src/test/java/com/example/coupon/CouponApplicationTests.java`

**Interfaces:**
- Consumes: Task 1의 의존성(`spring-boot-testcontainers`, `org.testcontainers:mysql`), Task 2의 MySQL 자격증명(`coupon`/`coupon`/DB `coupon`)
- Produces: 테이블 `coupon(id, name, total_quantity)`, `coupon_issue(id, coupon_id, user_id, issued_at)` + 인덱스 `idx_coupon_issue_coupon_id`. 엔티티 `com.example.coupon.domain.Coupon`, `com.example.coupon.domain.CouponIssue` — 둘 다 필드와 protected 기본 생성자만 가진다. 테스트 클래스 `CouponApplicationTests`가 static `MySQLContainer` 필드 `mysql`을 노출하며 Task 4가 여기에 테스트를 추가한다.

- [ ] **Step 1: `application.yml` 작성**

```yaml
spring:
  application:
    name: coupon
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/coupon}
    username: ${DB_USERNAME:coupon}
    password: ${DB_PASSWORD:coupon}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 2: 엔티티 2개 작성**

`src/main/java/com/example/coupon/domain/Coupon.java`

```java
package com.example.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int totalQuantity;

    protected Coupon() {
    }
}
```

`src/main/java/com/example/coupon/domain/CouponIssue.java`

```java
package com.example.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Coupon 연관관계를 걸지 않는다. lazy 프록시 초기화 쿼리가 Phase별 TPS 비교에 노이즈를 만든다.
    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    protected CouponIssue() {
    }
}
```

getter와 생성자는 만들지 않는다. Phase 0에는 이 엔티티를 만들거나 읽는 코드가 없고, `ddl-auto: validate`는 매핑만 검사한다.

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/java/com/example/coupon/CouponApplicationTests.java`

```java
package com.example.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CouponApplicationTests {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 마이그레이션이_적용되고_엔티티_매핑이_스키마와_일치한다() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = database()",
                String.class);

        assertThat(tables).contains("coupon", "coupon_issue");
    }
}
```

이 테스트는 assert보다 컨텍스트 로드 자체가 더 많은 것을 검증한다. `ddl-auto: validate`가 엔티티와 스키마의 불일치를 발견하면 테스트 본문에 도달하기 전에 실패한다.

- [ ] **Step 4: 실패 확인**

```powershell
.\gradlew.bat --no-daemon test --tests 'com.example.coupon.CouponApplicationTests' -i 2>&1 | Select-String -Pattern 'missing table|Schema-validation|FAILED|BUILD'
```

기대: FAIL. 원인은 `Schema-validation: missing table [coupon]`.
Flyway가 적용할 마이그레이션이 하나도 없어 테이블이 만들어지지 않았기 때문이다.

- [ ] **Step 5: `V1__init.sql` 작성**

`src/main/resources/db/migration/V1__init.sql`

```sql
create table coupon (
    id             bigint       not null auto_increment,
    name           varchar(100) not null,
    total_quantity int          not null,
    primary key (id)
) engine = innodb;

create table coupon_issue (
    id        bigint      not null auto_increment,
    coupon_id bigint      not null,
    user_id   bigint      not null,
    issued_at datetime(6) not null,
    primary key (id),
    -- Phase 1의 countByCouponId()가 풀스캔이 되면 측정 대상이 동시성이 아니라 느린 쿼리가 된다.
    key idx_coupon_issue_coupon_id (coupon_id)
) engine = innodb;
```

`(coupon_id, user_id)` unique 제약을 넣지 않는다. Phase 2(c)의 비교군 재료다.

- [ ] **Step 6: 통과 확인**

```powershell
.\gradlew.bat --no-daemon test --tests 'com.example.coupon.CouponApplicationTests'
```

기대: `BUILD SUCCESSFUL`.

- [ ] **Step 7: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/main/resources/application.yml src/main/java/com/example/coupon/domain src/main/resources/db/migration src/test/java/com/example/coupon/CouponApplicationTests.java
git commit -m "$(printf 'feat: 쿠폰 도메인 엔티티와 초기 스키마\n\nddl-auto는 validate로 둔다. update는 스키마가 실행 이력에 따라 달라져\n벤치마크 재현성을 깬다. 마이그레이션 없이 테스트를 돌려\nSchema-validation: missing table 로 실패하는 것을 먼저 확인한 뒤 통과시켰다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 4: Actuator 헬스체크

`management.endpoints.web.exposure.include: health` 설정이 실제로 동작하는지, DB 컴포넌트가 헬스에 포함되는지 검증한다. 설정 오타는 부팅을 실패시키지 않으므로 테스트로 잡아야 한다.

**Files:**
- Modify: `src/test/java/com/example/coupon/CouponApplicationTests.java` (테스트 메서드 1개 추가)

**Interfaces:**
- Consumes: Task 3의 `CouponApplicationTests` (static `mysql` 컨테이너, `jdbcTemplate` 필드)
- Produces: `/actuator/health`가 200과 `db` 컴포넌트를 반환한다는 보장

- [ ] **Step 1: 테스트 추가**

HTTP 요청을 보내야 하므로 클래스 애노테이션을 먼저 바꾼다. Task 3에서는 웹 환경이 필요 없었다.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
```

추가할 import:

```java
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
```

`jdbcTemplate` 필드 아래에 추가:

```java
    @Autowired
    private TestRestTemplate restTemplate;
```

클래스 끝에 추가:

```java
    @Test
    void 헬스체크가_DB_상태를_포함해_UP을_반환한다() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).contains("\"db\"");
    }
```

- [ ] **Step 2: 통과 확인**

`application.yml`이 이미 actuator를 노출하도록 설정되어 있으므로 이 테스트는 바로 통과한다. red 단계가 없는 것이 정상이다 — 이 테스트는 새 동작을 만드는 것이 아니라 Task 3에서 넣은 설정을 고정하는 회귀 방지 테스트다.

```powershell
.\gradlew.bat --no-daemon test --tests 'com.example.coupon.CouponApplicationTests'
```

기대: 두 테스트 모두 통과, `BUILD SUCCESSFUL`.

- [ ] **Step 3: 설정이 실제로 작동함을 확인 (역방향 검증)**

테스트가 무의미하지 않다는 것을 확인한다. `application.yml`에서 `include: health`를 `include: info`로 잠시 바꾸고 테스트를 돌린다.

```powershell
.\gradlew.bat --no-daemon test --tests 'com.example.coupon.CouponApplicationTests' 2>&1 | Select-String -Pattern 'FAILED|BUILD'
```

기대: `헬스체크가_DB_상태를_포함해_UP을_반환한다` FAILED (404).
확인 후 `include: health`로 되돌리고 다시 통과시킨다.

- [ ] **Step 4: 로컬 실행으로 PROMPTS.md 검증 조건 충족**

PROMPTS.md의 Phase 0 검증은 "`docker compose up -d` 후 앱이 뜨고 헬스체크 200"이다. Testcontainers가 아닌 실제 compose MySQL로 확인한다.

```powershell
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
```

`mysql`이 healthy인 것을 확인한 뒤 백그라운드로 앱을 띄운다.

```powershell
.\gradlew.bat bootRun
```

앱이 기동되면 다른 셸에서:

```powershell
(Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing).StatusCode
(Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing).Content
```

기대: `200`, 그리고 `{"status":"UP","components":{"db":{"status":"UP",...}}}`.
`caching_sha2_password` 관련 접속 오류가 나면 `application.yml`의 `DB_URL` 기본값에
`?allowPublicKeyRetrieval=true`를 붙인다. 확인 후 앱을 종료한다.

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add src/test/java/com/example/coupon/CouponApplicationTests.java src/main/resources/application.yml
git commit -m "$(printf 'test: 헬스체크가 DB 상태를 포함하는지 검증\n\nactuator 노출 설정 오타는 부팅을 실패시키지 않아 테스트로만 잡힌다.\ninclude를 info로 바꿔 404가 나는 것까지 확인해 테스트가\n실제로 뭔가를 검증하고 있음을 확인했다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
```

---

### Task 5: 문서 초기화, 전체 검증, push

**Files:**
- Create: `docs/progress.md`
- Create: `docs/benchmark.md`

**Interfaces:**
- Consumes: Task 1~4의 산출물 전체
- Produces: Phase 1이 채워 넣을 벤치마크 표와 고정 조건. `docs/progress.md`의 "현재 단계" 줄이 CLAUDE.md가 요구하는 Phase 추적 지점이다.

- [ ] **Step 1: `docs/progress.md` 작성**

```markdown
# 진행 상황

현재 단계: **Phase 0 — 프로젝트 셋업**

Phase 완료 조건을 만족하지 못하면 다음으로 넘어가지 않는다.

## Phase 0 — 프로젝트 셋업

- [x] Gradle 프로젝트 뼈대 (Spring Boot 3.5, Java 21 toolchain)
- [x] docker-compose (MySQL 기본, Redis/Kafka는 profile)
- [x] 엔티티 2개 + Flyway V1 마이그레이션
- [x] Testcontainers 스키마 정합성 테스트
- [x] Actuator 헬스체크 + 테스트
- [ ] 전체 검증 절차 통과

## Phase 1 — 나이브 구현 + 실패 재현

- [ ] `count()` 기반 무락 발급 로직
- [ ] 1000 스레드 동시성 테스트에서 오버셀 재현 (`ExecutorService` + `CountDownLatch`)
- [ ] 실제 발급 건수를 `docs/benchmark.md`에 기록
- [ ] k6 부하 테스트로 TPS, p95, p99 측정

완료 조건: 오버셀이 실제로 발생하고 그 수치가 문서에 남아 있을 것.

## Phase 2 — DB 락으로 해결 (비교군)

- [ ] 비관적 락 (`SELECT ... FOR UPDATE`)
- [ ] 낙관적 락 (`@Version` + 재시도)
- [ ] DB unique 제약 (`coupon_id` + `user_id`)
- [ ] 세 방식 각각 동시성 테스트 통과 + k6 측정
- [ ] `innodb_lock_wait_timeout` 조정 후 조건에 기록
- [ ] gap lock 데드락 관찰 (`SHOW ENGINE INNODB STATUS`)
- [ ] 트레이드오프 표 (정합성/성능/구현복잡도/한계)

완료 조건: 오버셀 0건, TPS는 Phase 1보다 떨어져 있을 것.

## Phase 3 — Redis 원자 연산

- [ ] Lua 스크립트로 재고 차감 + 중복 발급 방지를 단일 원자 연산으로
- [ ] `INCR` 후 롤백 방식과의 차이 및 선택 이유 문서화
- [ ] Redis 성공 직후 앱 다운 시 정합성 취약점을 `docs/known-issues.md`에 기록
- [ ] Redisson 분산락과 성능 비교 (선택)

완료 조건: 오버셀 0건 + Phase 2 대비 TPS 개선 수치.

## Phase 4 — Kafka 비동기화

- [ ] 토픽/파티션 키 설계와 순서 보장 필요성 판단
- [ ] 멱등키 설계 (DB unique 제약 병행)
- [ ] 컨슈머 중복 수신 시 발급 1건 검증
- [ ] DLQ 동작 검증
- [ ] Redis 재고는 깎였는데 DB 저장이 실패한 경우의 보상
- [ ] ID 생성 전략 전환 여부를 측정 후 판단
- [ ] 컨슈머 lag 확인 방법을 README에 기록

완료 조건: p99 개선 수치 + 컨슈머 lag이 유입량을 따라잡는지 확인 + DLQ 검증.

## Phase 5 — MSA 분리

- [ ] 서비스 경계 후보 2~3개 제시 및 근거
- [ ] `coupon-service`, `notification-service` 분리 (각자 DB 소유)
- [ ] 이벤트 스키마 계약 모듈 (Avro/JSON Schema 중 택1 + 선택 이유)
- [ ] Micrometer Tracing + Zipkin 분산 추적
- [ ] 서비스 하나를 내렸을 때의 동작을 `docs/resilience.md`에 기록
```

- [ ] **Step 2: `docs/benchmark.md` 작성**

```markdown
# 벤치마크

## 측정 환경

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 5 5600X (6C/12T) |
| RAM | 16GB |
| OS | Windows 10 Pro |
| 인프라 | Docker Desktop, MySQL 8.4 컨테이너 |
| JVM | Java 21 (Gradle toolchain) |
| 앱 실행 | 로컬 (`./gradlew bootRun`) |

**한계.** 앱, MySQL 컨테이너, k6가 모두 같은 머신에서 돈다. k6가 CPU를 쓰면
그만큼 앱과 DB가 못 쓴다. 따라서 절대 수치는 의미가 없고 **Phase 간 상대 비교만 유효하다.**

## 고정 조건

Phase 간 비교가 성립하려면 아래가 같아야 한다. 바꿀 때는 결과 표의 비고에 적는다.

| 항목 | 값 |
|---|---|
| 쿠폰 총 수량 | 100 |
| k6 VU | 200 |
| k6 duration | 30s |
| 동시성 테스트 스레드 수 | 1000 |
| HikariCP `maximum-pool-size` | 10 |
| `innodb_flush_log_at_trx_commit` | 1 (기본값) |
| `innodb_buffer_pool_size` | 128MB (기본값) |
| `innodb_lock_wait_timeout` | 50 (기본값, Phase 2에서 조정 예정) |
| 기본 격리 수준 | REPEATABLE READ |

`innodb_flush_log_at_trx_commit`을 2로 바꾸면 TPS가 크게 오르지만
그건 개선이 아니라 내구성을 팔아 얻은 수치다. 기본값을 지킨다.

## 결과

| Phase | 방식 | TPS | p95 | p99 | 오버셀 | 비고 |
|-------|------|-----|-----|-----|--------|------|
```

- [ ] **Step 3: 전체 검증 절차 실행**

스펙 8절의 순서를 처음부터 끝까지 다시 돌린다. 중간 상태가 아니라 깨끗한 상태에서 통과하는지 확인한다.

```powershell
docker compose down -v; if ($?) { docker compose up -d }
```

30초 정도 기다린 뒤:

```powershell
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
```

기대: `mysql`만 `(healthy)`.

```powershell
.\gradlew.bat --no-daemon clean test
```

기대: `BUILD SUCCESSFUL`, 테스트 2개 통과.

```powershell
.\gradlew.bat bootRun
```

다른 셸에서:

```powershell
(Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing).StatusCode
```

기대: `200`. 확인 후 앱 종료.

- [ ] **Step 4: `docs/progress.md`의 현재 단계를 Phase 1로 갱신**

Phase 0의 마지막 체크박스를 채우고 최상단 줄을 바꾼다. CLAUDE.md 기준 이 갱신을 빠뜨리면 다음 세션에서 Phase를 앞질러 가게 된다.

```markdown
현재 단계: **Phase 1 — 나이브 구현 + 실패 재현**
```

Phase 0 섹션의 마지막 항목:

```markdown
- [x] 전체 검증 절차 통과
```

- [ ] **Step 5: 커밋 및 push**

```bash
cd "C:/Users/song/Desktop/portfolioPjt"
git add docs/progress.md docs/benchmark.md
git commit -m "$(printf 'docs: 진행 상황과 벤치마크 양식 초기화\n\n벤치마크 고정 조건을 표로 못박았다. 앱과 DB와 k6가 한 머신에서\n도는 한 절대 수치는 의미가 없으므로 Phase 간 상대 비교만\n유효하다는 한계를 상단에 명시했다.\n\nPhase 0 검증을 모두 통과해 현재 단계를 Phase 1로 넘긴다.\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>')"
git push origin main
```

```bash
git log --oneline
```

기대: Task 1~5의 커밋 5개 + 기존 설계 문서 커밋 2개.

---

## Phase 0 완료 조건 (스펙 8절)

전부 통과해야 Phase 1로 넘어간다.

- [ ] `docker info`가 응답한다
- [ ] `docker compose up -d` → `mysql`만 healthy, Redis/Kafka는 기동되지 않는다
- [ ] `.\gradlew.bat bootRun` → Flyway `V1__init` 적용, Hibernate `validate` 통과, 8080 기동
- [ ] `/actuator/health` → 200, `status: UP`, `components.db.status: UP`
- [ ] `.\gradlew.bat clean test` → 테스트 2개 통과
- [ ] compose profile 스모크 체크 (Redis/Kafka 기동 확인 후 정리)
- [ ] `origin/main`에 push 완료
