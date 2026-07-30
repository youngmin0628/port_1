# Phase 0 — 프로젝트 셋업 설계

작성일: 2026-07-30
상태: 승인됨

## 1. 목표

선착순 쿠폰 발급 시스템의 뼈대를 만든다. 발급 로직은 만들지 않는다.

Phase 0의 산출물은 **"Phase 1에서 오버셀을 재현할 수 있는 최소한의 무대"** 다.
동작하는 애플리케이션, 재현 가능한 스키마, 그리고 이후 모든 Phase가 같은 조건에서
측정되도록 고정된 설정값이 있으면 끝이다.

## 2. 범위

### 하는 것

- Gradle 단일 모듈 Spring Boot 프로젝트 (3.5 계열 최신 패치, Java 21 toolchain)
- 도메인 엔티티 2개: `Coupon`, `CouponIssue`
- Flyway 마이그레이션 `V1__init.sql`
- `docker-compose.yml` — PostgreSQL / Redis / Kafka (Redis·Kafka는 profile로 분리)
- Actuator 헬스체크 엔드포인트
- Testcontainers 기반 컨텍스트 로드 테스트
- `docs/progress.md`, `docs/benchmark.md` 초기화
- git 저장소 초기화 및 최초 커밋

### 하지 않는 것 (그리고 이유)

| 항목 | 왜 안 하는가 |
|---|---|
| 발급 API / 서비스 / 리포지토리 | Phase 1의 작업. 나이브 구현의 실패를 먼저 재현해야 한다 |
| Redis / Kafka **의존성 및 코드** | Phase 3 / Phase 4. 컨테이너만 준비하고 앱은 이들의 존재를 모른다 |
| `(coupon_id, user_id)` unique 제약 | Phase 2(c)의 비교군 재료다. 미리 넣으면 비교군이 사라진다 |
| 앱 Dockerfile / k8s 매니페스트 | k8s 이행이 실제로 필요해지는 시점에 추가. Phase 1~4는 코드 수정 반복이 잦아 이미지 재빌드가 순수 낭비다 |
| 전역 예외 핸들러 / 커스텀 예외 / 검증 | 처리할 요청이 아직 없다 |
| `CouponIssueStrategy` 류 인터페이스 | 구현체가 2개 이상 실제로 필요해지는 Phase 2에서 추출한다 |
| Swagger, 관리자 API, 로그인 | 요청되지 않았다 |

## 3. 결정 사항과 근거

### 3.1 앱은 로컬 실행, 인프라만 컨테이너

`docker compose`는 PostgreSQL만 띄우고 앱은 `./gradlew bootRun`으로 로컬에서 돈다.

Phase 1~4는 코드를 고치고 부하를 걸고 수치를 보는 루프를 수십 번 반복한다.
앱을 컨테이너에 넣으면 그 루프마다 이미지 재빌드가 끼어든다.
설정을 전부 환경변수로 외부화해 두면 나중에 컨테이너/k8s로 옮길 때 코드 변경이 없다.

Dockerfile은 k8s가 실제로 필요해지는 시점에 추가한다.

### 3.2 PostgreSQL 17

MySQL이어야 할 이유가 없고, 이 프로젝트 목적에는 PostgreSQL이 유리한 점이 있다.
Phase 2에서 락 경쟁이 실제로 어디서 발생하는지 관찰할 때
`pg_locks` / `pg_stat_activity.wait_event`가 MySQL `performance_schema.data_locks`보다
다루기 쉽다.

이 선택으로 생기는 차이:

- **기본 격리 수준이 READ COMMITTED** (MySQL은 REPEATABLE READ).
  Phase 1 오버셀은 양쪽 모두 재현되며, read-modify-write 설명이 더 직관적이다.
- **Phase 2에서 `lock_timeout`을 명시해야 한다.** PostgreSQL 기본값은 무한 대기(`0`)라
  1000 스레드 비관적 락 테스트가 응답 없이 멈춘다. Phase 2 진입 시 처리한다.
- **포기하는 것**: MySQL InnoDB gap lock 데드락. Phase 2 학습거리로 흥미로웠지만 필수는 아니다.

### 3.3 Java 21 — Gradle toolchain으로 확보

로컬 JDK는 24지만 CLAUDE.md 스택은 21이다.
`build.gradle`에 toolchain 21을 선언하고 `settings.gradle`에 foojay-resolver 플러그인을 붙여
로컬에 21이 없으면 Gradle이 첫 빌드에 받아오게 한다.

측정이 생명인 프로젝트에서 **밑바닥 JDK가 바뀌어도 벤치마크 조건이 고정되는 것**이 핵심이다.
JDK를 손으로 설치하면 나중에 밀려서 Phase 1 수치와 Phase 4 수치의 런타임이 달라질 수 있다.

Gradle 자체는 로컬 JDK 24 위에서 돌므로 Gradle 8.14 이상이 필요하다.

### 3.4 Gradle wrapper 부트스트랩

로컬에 Gradle이 전역 설치되어 있지 않아 `gradle wrapper`를 실행할 수 없다.
`start.spring.io`에서 프로젝트를 내려받아 wrapper(`gradlew`, `gradle/wrapper/*`)를 확보한다.
이후 빌드는 wrapper로 진행한다.

실패 시 대안: Gradle 배포 zip을 직접 내려받아 한 번만 `gradle wrapper`를 실행한다.

### 3.5 스키마는 Flyway, `ddl-auto: validate`

`ddl-auto: update`는 스키마가 실행 이력에 따라 달라져 벤치마크 재현성을 깬다.
Flyway는 Phase 2에서 실제로 쓰인다 — unique 제약과 `@Version` 컬럼이 그때 추가된다.

`ddl-auto: validate`는 부팅 시 Flyway 스키마와 JPA 엔티티 매핑의 불일치를 잡는다.
Phase 0 테스트가 assert 없이도 의미를 갖는 이유다.

### 3.6 `coupon_id`는 연관관계가 아니라 plain `Long`

`CouponIssue.couponId`를 `@ManyToOne Coupon`으로 두지 않는다.

- 연관관계를 걸면 lazy 프록시 초기화 쿼리가 끼어들어 Phase별 TPS 비교에 노이즈가 생긴다
- Phase 5에서 서비스가 분리되면 어차피 객체 그래프가 끊기고 DB도 갈린다

### 3.7 `coupon_issue.coupon_id` 인덱스는 Phase 0부터 넣는다

Phase 1의 `countByCouponId()`가 풀스캔이 되면
측정 대상이 "동시성 병목"이 아니라 "인덱스 없음"이 된다.
Phase 1이 재현해야 하는 실패는 read-modify-write race이지 느린 쿼리가 아니다.

### 3.8 ID 생성 전략은 `IDENTITY` (나이브 선택)

PostgreSQL에서 `GenerationType.IDENTITY`는 Hibernate의 JDBC batch insert를 무력화한다.
그럼에도 Phase 0에서는 IDENTITY로 간다 — 가장 단순하고, 이 프로젝트는
나이브한 선택의 대가를 측정으로 드러내는 것이 목적이다.

**Phase 4 후보로 기록**: 컨슈머 배치 삽입이 병목으로 측정되면 pooled 시퀀스로 전환하고
before/after를 `docs/benchmark.md`에 남긴다. 지금 미리 바꾸지 않는다.

### 3.9 HikariCP 풀 크기를 10으로 명시

커넥션 풀 크기는 Phase 1~2 TPS를 지배하는 변수다.
기본값에 맡기면 나중에 수치의 출처를 설명할 수 없다.
`DB_POOL_SIZE` 환경변수로 조절 가능하게 하되 기본값 10을 벤치마크 조건에 기록한다.

PostgreSQL은 커넥션당 프로세스를 쓰므로 MySQL보다 커넥션 비용이 크다.
이 값이 Phase 1~2에서 더 뚜렷한 영향을 낼 가능성이 있다.

### 3.10 Redis / Kafka는 compose에 정의하되 profile로 분리

`docker compose up -d` 기본 기동은 PostgreSQL만.
Redis는 `--profile redis`, Kafka는 `--profile kafka`로 해당 Phase에서 켠다.

Phase 1~2 벤치마크가 유휴 Kafka 브로커와 CPU/메모리를 나눠 쓰지 않게 하려는 것이다.
Kafka(KRaft) 단일 노드만으로도 로컬에서 1GB 안팎을 잡는다.

## 4. 파일 구조

```
portfolioPjt/
├── build.gradle
├── settings.gradle
├── gradlew, gradlew.bat, gradle/wrapper/
├── docker-compose.yml
├── .gitignore
├── CLAUDE.md, PROMPTS.md            (기존)
├── docs/
│   ├── progress.md                  (신규)
│   ├── benchmark.md                 (신규, 표 헤더만)
│   └── superpowers/specs/           (이 문서)
└── src/
    ├── main/java/com/example/coupon/
    │   ├── CouponApplication.java
    │   └── domain/
    │       ├── Coupon.java
    │       └── CouponIssue.java
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/V1__init.sql
    └── test/java/com/example/coupon/
        └── CouponApplicationTests.java
```

리포지토리 패키지는 만들지 않는다. Phase 1에서 `countByCouponId()`가 필요해질 때 추가한다.

## 5. 상세 설계

### 5.1 의존성

| 스코프 | 아티팩트 |
|---|---|
| implementation | `spring-boot-starter-web` |
| implementation | `spring-boot-starter-data-jpa` |
| implementation | `spring-boot-starter-actuator` |
| implementation | `flyway-core` |
| runtimeOnly | `flyway-database-postgresql` |
| runtimeOnly | `org.postgresql:postgresql` |
| testImplementation | `spring-boot-starter-test` |
| testImplementation | `spring-boot-testcontainers` |
| testImplementation | `org.testcontainers:junit-jupiter` |
| testImplementation | `org.testcontainers:postgresql` |

Flyway 10 이상은 DB별 모듈이 분리되어 `flyway-database-postgresql`이 필요하다.
버전은 Spring Boot BOM이 관리한다. 구현 시 실제 아티팩트명을 확인한다.

Redis / Kafka 관련 의존성은 하나도 넣지 않는다.

### 5.2 엔티티

**`Coupon`**

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `Long` | PK, `GenerationType.IDENTITY` |
| `name` | `String` | not null, length 100 |
| `totalQuantity` | `int` | not null |

**`CouponIssue`**

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `Long` | PK, `GenerationType.IDENTITY` |
| `couponId` | `Long` | not null (연관관계 아님) |
| `userId` | `Long` | not null |
| `issuedAt` | `LocalDateTime` | not null |

`BaseEntity`, `createdAt`/`updatedAt` 감사 컬럼, 소프트 딜리트 플래그는 넣지 않는다.

**필드와 JPA 요구사항인 protected 기본 생성자만 만든다.**
getter와 생성자/정적 팩터리는 넣지 않는다 — Phase 0에는 엔티티를 만들거나 읽는 코드가
하나도 없다. `ddl-auto: validate`는 매핑만 검사하므로 이것으로 충분하고,
소비 코드가 생기는 Phase 1에서 필요한 만큼만 추가한다.

### 5.3 `V1__init.sql`

```sql
create table coupon (
    id             bigint generated by default as identity primary key,
    name           varchar(100) not null,
    total_quantity int          not null
);

create table coupon_issue (
    id        bigint generated by default as identity primary key,
    coupon_id bigint    not null,
    user_id   bigint    not null,
    issued_at timestamp not null
);

create index idx_coupon_issue_coupon_id on coupon_issue (coupon_id);
```

`generated by default as identity`를 쓴다 — Hibernate `IDENTITY` 전략과 맞는다.
`issued_at`은 `timestamp`(timezone 없음)로 `LocalDateTime`에 대응시킨다.
`(coupon_id, user_id)` unique 제약은 **넣지 않는다.**

### 5.4 `application.yml`

```yaml
spring:
  application:
    name: coupon
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/coupon}
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

접속 정보는 전부 환경변수로 외부화한다 — 나중에 컨테이너/k8s로 옮길 때 코드 변경이 없다.
Actuator는 `health`만 노출한다. `show-details: always`는 로컬 개발용이며
Phase 0 검증에서 `db` 컴포넌트 상태를 직접 확인하는 데 쓴다.

### 5.5 `docker-compose.yml`

| 서비스 | 이미지 | profile | 포트 | 비고 |
|---|---|---|---|---|
| `postgres` | `postgres:17-alpine` | (기본) | 5432 | `pg_isready` 헬스체크, named volume |
| `redis` | `redis:7.4-alpine` | `redis` | 6379 | Phase 3에서 기동 |
| `kafka` | `apache/kafka:3.9.0` | `kafka` | 9092 | KRaft 단일 노드(broker+controller 결합) |

PostgreSQL 자격증명은 `coupon` / `coupon` / DB `coupon`.
데이터는 named volume `postgres-data`에 두고, 초기화는 `docker compose down -v`로 한다.

Kafka는 KRaft 단일 노드로 `KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES=broker,controller`,
리스너 3종(`KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`,
`KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`), `KAFKA_CONTROLLER_QUORUM_VOTERS`,
`KAFKA_CONTROLLER_LISTENER_NAMES`, 복제계수 1을 설정한다.

### 5.6 테스트

`CouponApplicationTests`: `@SpringBootTest` + `@Testcontainers`,
`@ServiceConnection`을 붙인 `PostgreSQLContainer<>("postgres:17-alpine")` 정적 컨테이너,
`contextLoads()` 빈 테스트 메서드 하나.

assert가 없어도 의미가 있다. 이 테스트는 **로컬 DB 상태와 무관하게
Flyway 스키마와 JPA 엔티티 매핑이 일치한다**는 것을 증명한다.
불일치하면 `ddl-auto: validate`가 컨텍스트 로드를 실패시킨다.

동시성 테스트는 Phase 1의 작업이다.

### 5.7 `.gitignore`

`build/`, `.gradle/`, `.idea/`, `*.log`, `out/`.
`gradle/wrapper/gradle-wrapper.jar`와 `gradlew`는 **커밋한다** — wrapper의 존재 이유다.

### 5.8 문서 초기화

**`docs/progress.md`** — 최상단에 현재 단계를 기록한다.

```
현재 단계: Phase 0 — 프로젝트 셋업
```

Phase별 완료 조건과 체크 상태를 함께 둔다.

**`docs/benchmark.md`** — CLAUDE.md에 정의된 표 헤더와 측정 조건 기록 양식만 만든다.
수치는 Phase 1에서 채운다. 조건이 다른 수치를 비교하지 않기 위해
행마다 VU 수 / duration / 쿠폰 수량 / 커넥션 풀 크기를 함께 적는다.

| Phase | 방식 | TPS | p95 | p99 | 오버셀 | 비고 |
|-------|------|-----|-----|-----|--------|------|

### 5.9 에러 처리

**Phase 0에는 없다.** 전역 예외 핸들러, 커스텀 예외, 요청 검증을 만들지 않는다.
처리할 요청이 아직 없다.

인프라 장애는 Actuator가 다룬다 — DB 연결이 끊기면 `/actuator/health`가 `DOWN`을 반환한다.
DB가 없을 때 Flyway/Hibernate가 부팅을 실패시키는 것도 의도된 동작이다.
`try-catch`로 감싸 부팅을 통과시키지 않는다.

## 6. 검증 절차 (Phase 0 완료 조건)

순서대로 전부 통과해야 Phase 1로 넘어간다.

1. `docker compose up -d` → `postgres`만 기동되고 healthy 상태가 된다
   (`docker compose ps`로 확인). Redis/Kafka는 기동되지 않는다.
2. `./gradlew bootRun` → Flyway가 `V1__init` 을 적용하고,
   Hibernate `validate`가 통과하고, 앱이 8080에서 기동된다.
3. `curl localhost:8080/actuator/health` → HTTP 200, `status: UP`,
   `components.db.status: UP`.
4. `./gradlew test` → Testcontainers PostgreSQL 위에서 컨텍스트 로드 테스트 통과.
5. compose YAML 스모크 체크(1회):
   `docker compose --profile redis --profile kafka up -d` → redis/kafka 모두 기동 확인
   → `docker compose --profile redis --profile kafka down`.
   Phase 3/4에서 처음 삽을 파지 않기 위한 확인이다.
6. `git init` 후 최초 커밋 완료.

## 7. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 첫 빌드에 네트워크 필요 (JDK 21 자동 확보, 의존성, `start.spring.io`) | 셋업 차단 | 실패 시 Gradle 배포 zip 직접 내려받아 wrapper 생성 |
| k6 미설치 | Phase 1의 부하 측정 차단 (Phase 0은 무관) | Phase 1 진입 시 `winget install k6` 안내 |
| `flyway-database-postgresql` 아티팩트명/버전 | 빌드 실패 | 구현 시 Spring Boot BOM 기준으로 확인 |
| Kafka KRaft 환경변수 오타 | Phase 4에서 발견 | 검증 절차 5번으로 Phase 0에 앞당겨 확인 |

## 8. 다음 Phase로 넘기는 항목

- **Phase 1**: 리포지토리, 발급 서비스/컨트롤러, 동시성 테스트, k6 스크립트, 오버셀 재현 수치
- **Phase 2**: `lock_timeout` 설정, `(coupon_id, user_id)` unique 제약,
  `@Version` 컬럼, 락 구현체 3종
- **Phase 3**: Redis 의존성 및 Lua 스크립트, `docker compose --profile redis`
- **Phase 4**: Kafka 의존성 및 프로듀서/컨슈머, `docker compose --profile kafka`,
  ID 생성 전략을 pooled 시퀀스로 전환할지 측정 후 판단
- **Phase 5 또는 k8s 이행 시점**: 앱 Dockerfile, k8s 매니페스트
