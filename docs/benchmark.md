# 벤치마크

## 측정 환경

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 5 5600X (6C/12T) |
| RAM | 16GB |
| OS | Windows 10 Pro |
| 인프라 | Docker Desktop, MySQL 8.4.10 컨테이너 |
| JVM | Temurin 21.0.11 (Gradle toolchain으로 확보) |
| 앱 실행 | 로컬 (`gradlew bootRun`), 컨테이너 아님 |

**한계.** 앱, MySQL 컨테이너, k6가 모두 같은 머신에서 돈다.
k6가 CPU를 쓰면 그만큼 앱과 DB가 못 쓴다.
따라서 절대 수치는 의미가 없고 **Phase 간 상대 비교만 유효하다.**
다른 환경에서 측정한 수치와 이 표를 나란히 놓지 않는다.

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

Phase 0은 발급 로직이 없어 측정 대상이 아니다. 첫 행은 Phase 1에서 채운다.
