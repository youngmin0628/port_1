# Phase 0 — 프로젝트 셋업 설계

작성일: 2026-07-30

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
- `docker-compose.yml` — MySQL / Redis / Kafka (Redis·Kafka는 profile로 분리)
- Actuator 헬스체크 엔드포인트
- Testcontainers 기반 컨텍스트 로드 테스트
- `docs/progress.md`, `docs/benchmark.md` 초기화
- git 저장소 초기화, `origin` 등록, 최초 push

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

`docker compose`는 MySQL만 띄우고 앱은 `./gradlew bootRun`으로 로컬에서 돈다.

Phase 1~4는 코드를 고치고 부하를 걸고 수치를 보는 루프를 수십 번 반복한다.
앱을 컨테이너에 넣으면 그 루프마다 이미지 재빌드가 끼어든다.
설정을 전부 환경변수로 외부화해 두면 나중에 컨테이너/k8s로 옮길 때 코드 변경이 없다.

Dockerfile은 k8s가 실제로 필요해지는 시점에 추가한다.

### 3.2 MySQL 8.4 (LTS)

PostgreSQL을 한 번 검토했다가 MySQL로 되돌렸다.
로컬 비용 차이는 디스크 약 300MB, 램 약 300MB 수준인데
로컬은 디스크 여유 191GB / 램 16GB다. 무의미한 차이다.

반대로 학습거리 차이는 크다. 이 프로젝트는 학습·포트폴리오 목적이므로 그쪽을 택한다.

**MySQL을 택해서 얻는 것:**

- **gap lock / next-key lock.** InnoDB는 기본 격리 수준 REPEATABLE READ에서
  인덱스 레코드뿐 아니라 레코드 사이의 간격까지 잠근다.
  Phase 2에서 비관적 락을 제대로 걸었는데도 예상 못 한 데드락이 발생할 수 있고,
  그 원인을 `SHOW ENGINE INNODB STATUS`의 `LATEST DETECTED DEADLOCK` 섹션에서
  직접 읽어내는 것이 이 프로젝트에서 가장 값진 학습 대목이 된다.
  PostgreSQL에는 gap lock이 없어 이 함정 자체가 존재하지 않는다.
- **기본 격리 수준이 REPEATABLE READ.** Phase 1 오버셀을 설명할 때
  consistent read snapshot 때문에 `count()`가 무엇을 보는지까지 따져야 한다.
  설명할 것이 더 많다는 뜻이고, 그게 이 프로젝트에는 이득이다.
- **국내 실무 빈도.** 포트폴리오를 읽는 사람에게 익숙한 스택이다.

**대가:**

- 락 관찰이 PostgreSQL의 `pg_locks`보다 번거롭다.
  `performance_schema.data_locks`, `performance_schema.data_lock_waits`,
  `information_schema.innodb_trx`, `sys.innodb_lock_waits`를 쓴다.
  번거롭긴 하지만 이것도 익힐 값이 있는 도구다.
- `innodb_lock_wait_timeout` 기본값이 50초다. Phase 2에서 1000 스레드가 대기에 걸리면
  테스트가 오래 끌린다. Phase 2 진입 시 이 값을 낮춰 측정하고 조건에 기록한다.
  (참고로 PostgreSQL은 `lock_timeout` 기본값이 무한이라 아예 멈춘다.
  MySQL 쪽이 이 점에서는 안전하다.)

### 3.3 Java 21 — Gradle toolchain으로 확보

로컬 JDK는 24지만 이 프로젝트가 정한 스택은 21이다.
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

부수 효과로, 이 인덱스가 Phase 2 gap lock의 잠금 범위를 결정한다.
인덱스 유무가 락 범위를 바꾼다는 것 자체가 Phase 2의 관찰 대상이 된다.

### 3.8 ID 생성 전략은 `IDENTITY` (나이브 선택)

`GenerationType.IDENTITY`(MySQL AUTO_INCREMENT)는 Hibernate의 JDBC batch insert를
무력화한다. insert 직후 생성된 키를 받아와야 하므로 배치로 묶을 수 없다.

그럼에도 Phase 0에서는 IDENTITY로 간다 — 가장 단순하고, 이 프로젝트는
나이브한 선택의 대가를 측정으로 드러내는 것이 목적이다.

**Phase 4 후보로 기록.** MySQL에는 시퀀스가 없어 PostgreSQL처럼 pooled 시퀀스로
갈아탈 수 없다. 컨슈머의 배치 삽입이 병목으로 측정되면 선택지는 셋이다.

1. 애플리케이션이 ID를 직접 할당(TSID 등) + `rewriteBatchedStatements=true`
2. `TABLE` generator — 그 자체가 경쟁 지점이라 이 프로젝트 취지에 맞지 않는다
3. IDENTITY를 유지하고 배치를 포기

Phase 4에서 측정한 뒤 판단하고 before/after를 `docs/benchmark.md`에 남긴다.
지금 미리 바꾸지 않는다.

### 3.9 HikariCP 풀 크기를 10으로 명시

커넥션 풀 크기는 Phase 1~2 TPS를 지배하는 변수다.
기본값에 맡기면 나중에 수치의 출처를 설명할 수 없다.
`DB_POOL_SIZE` 환경변수로 조절 가능하게 하되 기본값 10을 벤치마크 조건에 기록한다.

### 3.10 MySQL 기본 설정을 건드리지 않는다

`innodb_flush_log_at_trx_commit`은 기본값 1(커밋마다 fsync)을 유지한다.
2로 바꾸면 TPS가 크게 오르지만 그건 개선이 아니라 내구성을 팔아 얻은 수치다.
`innodb_buffer_pool_size`도 기본값 128MB를 유지한다 — 이 규모의 테이블에는 충분하다.

두 값 모두 `docs/benchmark.md`의 측정 조건에 기록해서
나중에 누가 이 수치를 보더라도 무엇을 전제한 수치인지 알 수 있게 한다.

### 3.11 Redis / Kafka는 compose에 정의하되 profile로 분리

`docker compose up -d` 기본 기동은 MySQL만.
Redis는 `--profile redis`, Kafka는 `--profile kafka`로 해당 Phase에서 켠다.

Phase 1~2 벤치마크가 유휴 Kafka 브로커와 CPU/메모리를 나눠 쓰지 않게 하려는 것이다.
Kafka(KRaft) 단일 노드만으로도 로컬에서 1GB 안팎을 잡는다.

## 4. 코드 / 주석 / 문서 컨벤션

이 프로젝트는 사람이 읽고 평가하는 포트폴리오다.
기계가 생성한 티가 나는 코드는 그 자체로 감점 요소다. 아래를 지킨다.

### 4.1 주석

**주석은 "왜"만 쓴다.** 코드가 무엇을 하는지 한국어로 번역하는 주석은 쓰지 않는다.

```java
// 쓰지 않는다 — 코드가 이미 말하고 있다
// 쿠폰 발급 수를 조회한다
long issued = couponIssueRepository.countByCouponId(couponId);
```

**이 프로젝트에서 주석이 정당한 경우는 셋뿐이다.**

1. 의도적으로 취약하거나 비효율적으로 만든 코드 — Phase 1의 무락 구현이 대표적이다
2. 트레이드오프가 있는 선택의 이유 — 왜 이 방식을 골랐는지가 코드로는 안 보일 때
3. 직관에 반하는 동작 — 격리 수준, 락 범위, 배치 무력화처럼 몰랐으면 당한다는 것들

그 밖의 경우에 주석을 달고 싶어지면 주석 대신 이름을 고친다.

### 4.2 금지

- 형식적 Javadoc. 게터, 생성자, 엔티티, 컨트롤러 메서드에 붙이지 않는다.
  공개 API 중 계약이 자명하지 않은 것에만 쓴다.
- 섹션 구분 주석(`// ===== 필드 =====`)과 장식용 구분선
- 이모지, 체크마크, 화살표 같은 장식 문자
- `// TODO`, `// FIXME`. 남길 것이 있으면 `docs/known-issues.md`에 문장으로 쓴다
- 발생 불가능한 시나리오의 null 체크와 try-catch

### 4.3 이름

도메인 용어로 짧게 쓴다. `couponIssueRequestDtoList` 대신 `requests`.
`DTO` 접미사는 실제로 계층 경계를 넘는 타입에만 붙인다.
축약하지 말라는 뜻이 아니라, 타입이 이미 말하는 것을 이름에 반복하지 말라는 뜻이다.

### 4.4 커밋 메시지

한국어로 쓴다. 제목은 무엇을 했는지 한 줄.
본문은 그 이유가 코드나 제목에서 자명하지 않을 때만 쓰고, 불릿을 기계적으로 나열하지 않는다.

커밋 하나가 하나의 변경을 담게 한다. 여러 관심사를 한 커밋에 섞으면
나중에 "이 결정을 왜 했더라"를 히스토리에서 되짚을 수 없다.
이 프로젝트에서 커밋 히스토리는 그 자체가 기록물이다.

### 4.5 문서

`docs/*.md`는 수치와 관찰 사실 중심으로 쓴다.
"혁신적", "완벽한", "강력한" 같은 과장 표현을 쓰지 않는다.
실패한 시도와 해결하지 못한 것도 그대로 남긴다 — 그게 이 프로젝트에서 가장 읽을 가치가 있는 부분이다.

## 5. 파일 구조

```
portfolioPjt/
├── build.gradle
├── settings.gradle
├── gradlew, gradlew.bat, gradle/wrapper/
├── docker-compose.yml
├── .gitignore
├── docs/
│   ├── progress.md                  (신규)
│   ├── benchmark.md                 (신규, 표 헤더와 측정 조건 양식만)
│   └── design/                      (이 문서)
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

## 6. 상세 설계

### 6.1 의존성

| 스코프 | 아티팩트 |
|---|---|
| implementation | `spring-boot-starter-web` |
| implementation | `spring-boot-starter-data-jpa` |
| implementation | `spring-boot-starter-actuator` |
| implementation | `flyway-core` |
| runtimeOnly | `flyway-mysql` |
| runtimeOnly | `com.mysql:mysql-connector-j` |
| testImplementation | `spring-boot-starter-test` |
| testImplementation | `spring-boot-testcontainers` |
| testImplementation | `org.testcontainers:junit-jupiter` |
| testImplementation | `org.testcontainers:mysql` |

Flyway 10 이상은 DB별 모듈이 분리되어 `flyway-mysql`이 필요하다.
버전은 Spring Boot BOM이 관리한다. 구현 시 실제 아티팩트명을 확인한다.

Redis / Kafka 관련 의존성은 하나도 넣지 않는다.

### 6.2 엔티티

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

### 6.3 `V1__init.sql`

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
    key idx_coupon_issue_coupon_id (coupon_id)
) engine = innodb;
```

`datetime(6)`은 Hibernate의 `LocalDateTime` 기본 매핑과 맞춘 것이다.
`(coupon_id, user_id)` unique 제약은 **넣지 않는다** — Phase 2(c)의 재료다.

### 6.4 `application.yml`

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

접속 정보는 전부 환경변수로 외부화한다 — 나중에 컨테이너/k8s로 옮길 때 코드 변경이 없다.
JDBC URL에 **성능 튜닝 파라미터를 붙이지 않는다.** 드라이버 기본값에서 출발한다.
(9절의 `allowPublicKeyRetrieval`은 튜닝이 아니라 접속 자체를 위한 것이므로 예외다.)

Actuator는 `health`만 노출한다. `show-details: always`는 로컬 개발용이며
Phase 0 검증에서 `db` 컴포넌트 상태를 직접 확인하는 데 쓴다.

### 6.5 `docker-compose.yml`

| 서비스 | 이미지 | profile | 포트 | 비고 |
|---|---|---|---|---|
| `mysql` | `mysql:8.4` | (기본) | 3306 | `mysqladmin ping` 헬스체크, named volume |
| `redis` | `redis:7.4-alpine` | `redis` | 6379 | Phase 3에서 기동 |
| `kafka` | `apache/kafka:3.9.0` | `kafka` | 9092 | KRaft 단일 노드(broker+controller 결합) |

MySQL은 `MYSQL_DATABASE=coupon`, `MYSQL_USER=coupon`, `MYSQL_PASSWORD=coupon`,
`MYSQL_ROOT_PASSWORD=root`로 띄우고 `--character-set-server=utf8mb4`를 준다.
성능 관련 파라미터(3.10)는 건드리지 않는다.

헬스체크는 `mysqladmin ping -h 127.0.0.1`로 TCP 리스너를 확인한다.
소켓이 아니라 TCP로 확인해야 앱이 붙을 수 있는 상태인지가 검증된다.

데이터는 named volume `mysql-data`에 두고, 초기화는 `docker compose down -v`로 한다.

Kafka는 KRaft 단일 노드로 `KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES=broker,controller`,
리스너 3종(`KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`,
`KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`), `KAFKA_CONTROLLER_QUORUM_VOTERS`,
`KAFKA_CONTROLLER_LISTENER_NAMES`, 복제계수 1을 설정한다.

### 6.6 `.gitignore`

`build/`, `.gradle/`, `.idea/`, `out/`, `*.log`.
`gradle/wrapper/gradle-wrapper.jar`와 `gradlew`는 **커밋한다** — wrapper의 존재 이유다.

### 6.7 테스트

`CouponApplicationTests`: `@SpringBootTest` + `@Testcontainers`,
`@ServiceConnection`을 붙인 `MySQLContainer<>("mysql:8.4")` 정적 컨테이너,
`contextLoads()` 메서드 하나.

assert가 없어도 의미가 있다. 이 테스트는 **로컬 DB 상태와 무관하게
Flyway 스키마와 JPA 엔티티 매핑이 일치한다**는 것을 증명한다.
불일치하면 `ddl-auto: validate`가 컨텍스트 로드를 실패시킨다.

동시성 테스트는 Phase 1의 작업이다.

### 6.8 문서 초기화

**`docs/progress.md`** — 최상단에 현재 단계를 기록한다.

```
현재 단계: Phase 0 — 프로젝트 셋업
```

Phase별 완료 조건과 체크 상태를 함께 둔다.
Phase가 바뀔 때마다 최상단 줄을 갱신한다. 빠뜨리면 다음 작업에서 단계를 앞질러 간다.

**`docs/benchmark.md`** — 아래 표 헤더와 측정 조건 양식만 만든다.
수치는 Phase 1에서 채운다.

| Phase | 방식 | TPS | p95 | p99 | 오버셀 | 비고 |
|-------|------|-----|-----|-----|--------|------|

측정 조건 양식에 아래를 고정 항목으로 둔다.

- k6 VU 수, duration, 쿠폰 총 수량
- HikariCP `maximum-pool-size`
- `innodb_flush_log_at_trx_commit`, `innodb_buffer_pool_size`
- 하드웨어: AMD Ryzen 5 5600X (6C/12T), 16GB RAM, Windows 10 Pro, Docker Desktop

**한 가지 정직하게 적어둘 것:** 앱, MySQL 컨테이너, k6가 모두 같은 머신에서 돈다.
k6가 CPU를 쓰면 그만큼 앱과 DB가 못 쓴다.
따라서 **절대 수치는 의미가 없고 Phase 간 상대 비교만 유효하다.**
이 한계를 `docs/benchmark.md` 상단에 명시한다.

### 6.9 에러 처리

**Phase 0에는 없다.** 전역 예외 핸들러, 커스텀 예외, 요청 검증을 만들지 않는다.
처리할 요청이 아직 없다.

인프라 장애는 Actuator가 다룬다 — DB 연결이 끊기면 `/actuator/health`가 `DOWN`을 반환한다.
DB가 없을 때 Flyway/Hibernate가 부팅을 실패시키는 것도 의도된 동작이다.
`try-catch`로 감싸 부팅을 통과시키지 않는다.

## 7. git / 원격 저장소

- 원격: `origin` = `https://github.com/youngmin0628/port_1.git` (확인 시점에 빈 저장소)
- 기본 브랜치: `main`
- Phase 단위로 커밋하고 Phase 완료 시 push한다

## 8. 검증 절차 (Phase 0 완료 조건)

순서대로 전부 통과해야 Phase 1로 넘어간다.

0. Docker Desktop 기동 (확인 시점에 꺼져 있었다). `docker info`가 응답할 것.
1. `docker compose up -d` → `mysql`만 기동되고 healthy 상태가 된다
   (`docker compose ps`로 확인). Redis/Kafka는 기동되지 않는다.
2. `./gradlew bootRun` → Flyway가 `V1__init`을 적용하고,
   Hibernate `validate`가 통과하고, 앱이 8080에서 기동된다.
3. `curl localhost:8080/actuator/health` → HTTP 200, `status: UP`,
   `components.db.status: UP`.
4. `./gradlew test` → Testcontainers MySQL 위에서 컨텍스트 로드 테스트 통과.
5. compose YAML 스모크 체크(1회):
   `docker compose --profile redis --profile kafka up -d` → redis/kafka 모두 기동 확인
   → `docker compose --profile redis --profile kafka down`.
   Phase 3/4에서 처음 삽을 파지 않기 위한 확인이다.
6. `origin`에 push 완료.

## 9. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 첫 빌드에 네트워크 필요 (JDK 21 자동 확보, 의존성, `start.spring.io`) | 셋업 차단 | 실패 시 Gradle 배포 zip을 직접 내려받아 wrapper 생성 |
| `caching_sha2_password` 인증 실패 | 앱이 DB에 붙지 못함 | 발생 시 JDBC URL에 `allowPublicKeyRetrieval=true` 추가. MySQL 8.4 + connector-j 조합에서 흔한 첫 관문이다 |
| `flyway-mysql` 아티팩트명/버전 | 빌드 실패 | 구현 시 Spring Boot BOM 기준으로 확인 |
| Kafka KRaft 환경변수 오타 | Phase 4에서 발견 | 검증 절차 5번으로 Phase 0에 앞당겨 확인 |
| k6 미설치 | Phase 1의 부하 측정 차단 (Phase 0은 무관) | Phase 1 진입 시 `winget install k6` |

## 10. 다음 Phase로 넘기는 항목

- **Phase 1**: 리포지토리, 발급 서비스/컨트롤러, 동시성 테스트, k6 스크립트, 오버셀 재현 수치
- **Phase 2**: `innodb_lock_wait_timeout` 조정, `(coupon_id, user_id)` unique 제약,
  `@Version` 컬럼, 락 구현체 3종, gap lock 데드락 관찰 및 기록
- **Phase 3**: Redis 의존성 및 Lua 스크립트, `docker compose --profile redis`
- **Phase 4**: Kafka 의존성 및 프로듀서/컨슈머, `docker compose --profile kafka`,
  ID 생성 전략 전환 여부를 측정 후 판단
- **Phase 5 또는 k8s 이행 시점**: 앱 Dockerfile, k8s 매니페스트
