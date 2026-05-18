---
name: verify-code-flow
description: |
  verify-code orchestrator 의 **Phase 2 sub-skill** — 흐름 / 동작 결함.
  자식 entity ID 동기화 / 멱등 / 사일런트 디폴트 (Boolean 반환 무시 포함) / 동시성·락 순서 / 자원 라이프사이클 / 시간·Clock / 예외·메시지 노출 / 트랜잭션·영속성 / 외부 입력 화이트리스트 / 보안.
user-invocable: true
---

# Phase 2 — Flow

## 자식 Entity / Aggregate 내부 식별자 동기화

`@OneToMany` / `@OneToOne` 자식이 부모 PK 를 들고 있을 때 *영속화 전 부모 PK = 0* 사실이 자주 잊혀진다. `child = ChildModel(parentId = this.id, ...)` 형태는 신규 부모에서 `parentId=0` 으로 굳는다. 부모 PK 컬럼은 `@JoinColumn` 단독 관리 + `insertable=false, updatable=false`, 자식의 `parentId` 는 읽기 전용. `cascade=ALL` 누락이면 부모 삭제 시 자식 고아, `orphanRemoval = false` 면 `_children.remove(...)` 가 DB 에 반영 안 됨.

## 멱등성 / 부수효과 일관성

같은 요청을 두 번 호출해도 도메인 상태가 일관되어야 한다. `wish` 멱등인데 카운트가 +2, `unwish` 가 미찜 상태에 호출되면 음수 진입, `cancel` 이 이미 취소된 상태에 호출되면 inventory 두 번 복원 등은 *상태 전이 검증* 누락 신호. 외부 호출 (SMS, 결제) 이 트랜잭션 중간에 일어나면 롤백 시 부수효과만 남는다.

멱등 진입점 = (1) 현재 상태 검사 → (2) 변화 없으면 noop → (3) 변화 있으면 한 번만.

## 사일런트 디폴트 / Boolean 반환값 무시

null / 빈 입력이 *조용히* 정상 값으로 변환되면 버그가 늦게 폭발한다. `convertToDatabaseColumn(null)` → `"{}"`, `find...()` 결과 null → 빈 객체, `?: defaultValue` 의 비즈니스 의미 미검토, `try { ... } catch { return emptyList() }` 같은 진짜 실패 무시 패턴은 모두 지적한다.

**Boolean / 결과 반환값을 받아놓고 무시하는 패턴이 가장 위험** — `latch.await(timeout)`, `tryLock()`, `compareAndSet()`, `Set.add()`, `Map.replace()`, `condition.await(timeout)`. timed wait 의 Boolean 을 무시하면 *타임아웃이 나도* 후속 라인이 실행되어 race window 가 안 만들어졌는데 테스트가 통과한다. 모든 timed wait / try-action 은 `check(...)` 또는 `assertThat(...).isTrue()` 로 어셔런스 강제. (PR9 Copilot #3/#5/#6/#8)

`Grep "\.await\(\d+,\s*TimeUnit" path=test glob="*Test.kt"` → 각 매치가 `check(...)` 로 감싸였는지 확인. 미감싸진 매치는 P1.

## 동시성 / 스레드 안전성

본 라운드(2~3주차)는 단일 스레드 가정이지만, 4주차에서 락/원자 연산이 들어올 자리를 본 라운드 코드가 막지 않게 한다. 도메인 모델의 `var` 필드 다중 스레드 동시 변경, 정적 mutable companion, `ThreadLocal` 누수, `lazy { }` 의 stateful 초기화 race, `@Async` 의 트랜잭션 컨텍스트 전파 가정, 컬렉션 순회 중 변경은 모두 지적.

**다일자 (다중 row) 비관적 락 시 `ORDER BY` 미명시** 면 두 트랜잭션이 *서로 다른 순서* 로 락 시도하다 InnoDB cycle 검출 → deadlock. 락 순서를 *SQL ORDER BY date ASC* 와 *Facade 진입점 `.sorted()`* 양쪽에 명시. 한쪽만 있으면 문서 ↔ 가드 정합 깨짐.

**운영 atomic UPDATE 의 InMemory 더블이 같은 인스턴스를 mutate** 하면 운영(stale entity) ↔ InMemory(mutated entity) 응답이 갈린다. atomic 호출 *이전* 의 값을 별도 변수 (`countBefore`) 로 박제하는 패턴이 정합.

**동시성 E2E 의 latch 동기화 정합** — `ready.await(5, SECONDS)` 의 Boolean 반환을 무시하면 일부 worker 가 도달 못 한 채 `start.countDown()` 호출 → 동시 출발이 안 된 채 race window 가 안 만들어지고 테스트가 silent 통과. `check(ready.await(timeout, SECONDS)) { "..." }` 로 모든 latch await 의 Boolean 을 어셔런스.

## 자원 라이프사이클

`Closeable` / `AutoCloseable` 자원이 `use { }` / try-with-resources 없이 사용되거나, 외부 클라이언트(HTTP, Redis, JDBC) connection / pool 정리 누락, 파일 / Stream close 누락, `Flux` / `Mono` dispose 누락, `Schedulers` 직접 생성 후 미종료는 모두 누수.

## 시간 / Clock / Timezone

도메인이 `LocalDateTime.now()` / `Instant.now()` 직접 호출하면 테스트에서 시간 고정 불가 — `Clock` 주입 받아 `LocalDateTime.now(clock)`. `config/ClockConfig.kt` 사용. `LocalDateTime` vs `ZonedDateTime` 무분별 혼용, legacy `java.util.Date`, KST/UTC 변환 없는 DB `TIMESTAMP` ↔ 도메인 `LocalDate` 매핑은 시간대 사고 원인. DATE 변환 시 명시적 `ZoneId.of("Asia/Seoul")`.

## 예외 처리 / Cause / 메시지 노출

`catch (e: Exception)` / `catch (e: Throwable)` 의 광범위 catch, catch 후 로그만 찍고 swallow, 도메인이 `RuntimeException` / `IllegalArgumentException` 직접 throw, `e.printStackTrace()`, **`cause` 보존 안 함**, `try { } catch { return null }` 패턴은 모두 지적.

**외부 입력 / 라이브러리 예외 메시지(`e.message`)를 사용자 응답에 노출** 하면 내부 정보·원본 데이터·스택 정보가 외부로 누출 — 보안 위험. 클라이언트 메시지는 일반화 ("정책 데이터 처리 실패"), 상세 원인은 로그 (`log.warn("...", e)`). **`CoreException.customMessage` 에 외부 식별자 (LoginId / email / userId / token) 직접 박는 패턴은 P1 보안 결함** — 메시지 일반화 + 식별자는 로그로.

**JPA 예외 (`OptimisticLockingFailureException` / `DataIntegrityViolationException` / `LockTimeoutException` / `PessimisticLockingFailureException`) 가 그대로 노출** 되면 응답이 Hibernate / Spring 내부 텍스트로 직행. Facade try/catch 로 `CoreException(CONFLICT, "<도메인 메시지>", cause = e)` 변환. 같은 도메인 사고가 여러 JPA 예외 경로로 발생할 수 있으면 *throw 메시지 동일* 하게 — 클라이언트 일관성, cause 의 분기 정보로 디버깅.

**Facade try/catch 의 catch scope** 가 도메인 사고의 *모든 throw 경로* 를 감싸야 한다. `model.someMethod()` 호출이 try *밖* 이고 자체 도메인 검증 throw 가 catch 안 잡혀 메시지 일관성 깨지는 패턴은 §0-B CE-1 의 회귀 영역 (Phase 4 cold-eye 가 마지막 확인).

**Converter 의 read 측만 try/catch, write 측은 raw 통과** 는 비대칭. `convertToEntityAttribute` 가 cause 보존하면서 `convertToDatabaseColumn` 의 `writeValueAsString` 예외가 무가공이면 정책이 갈린다. 로그 raw payload 는 길이 + 프리뷰만 (`dbData.take(PREVIEW_LIMIT)`).

**동시성 E2E 테스트의 광범위 catch** — `} catch (e: Exception) { conflicts.incrementAndGet() }` 으로 NPE / AssertionError / Spring 실패까지 conflict 로 흡수하면 회귀 신호가 silent. catch 의 의도된 카테고리만 conflicts, 그 외는 별도 `others` 카운터 + `others.isZero()` 어설션.

## 트랜잭션 / 영속성

`@Transactional` 은 Facade 한 곳에만. 도메인 서비스에 중첩 선언, `readOnly = true` 안에서 쓰기, 같은 클래스의 self-invocation, Lazy 컬렉션을 트랜잭션 밖 접근, `flush()` / `clear()` 직접 호출은 모두 지적.

**`@Lock(PESSIMISTIC_WRITE)` / `setLockMode(PESSIMISTIC_WRITE)` 메서드를 트랜잭션 밖에서 호출** 하면 락이 statement 종료 즉시 해제되어 비관적 락의 목적 자체가 무력화. **`saveAndFlush` 도 `@Transactional` 밖이면 자체 TX 로 commit 되어 호출 시점 throw 의미가 깨진다** — Facade try/catch 가 잡지 못함. 비관적 락 / saveAndFlush 의 트랜잭션 의무는 Repository 인터페이스 KDoc 에 박제.

## 캐시 결정 흐름 / N+1 / 결제 직전 DB 재확인 (week5 PR5 D-5 합류)

**N+1 회피 — 루프 안 Repository 호출 차단**: `.map { repo.findBy...(it) }` / `.forEach { repo.load(it) }` 같은 *컬렉션 순회 안의 단건 Repository 호출* 은 N+1. *projection 1쿼리* 또는 *batch IN (`findAllByIdIn`)* 으로 전환해야 한다 (week5 PR3 D-3 선례). 새 검색 / 상세 Facade 가 N+1 패턴으로 작성되면 *발행 SQL 어설션 통합 테스트* (Hibernate stat) 회귀 가드 의무.

`Grep "forEach\s*\{[^}]*Repository" glob="**/*.kt"` / `Grep "\.map\s*\{[^}]*Repository" glob="**/*.kt"` → 루프 안 Repository 호출 의심 패턴.

**결제 흐름 캐시 결정 금지 (D-5 contract)** — `ReservationFacade.reserve` / `cancel` 류의 *재고 / 금액 변경 흐름* 은 *cache 응답을 결정 근거로 사용 금지*. cache 의 stale window (PR4: `availability:*` 10s / `search:result:*` 5m) 가 결제 흐름의 결정 근거가 되면 *더블부킹*. 결정은 *비관적 락 + DB 직접* 만 (`findInventoriesForUpdate` 답습). 회귀 가드 = `ReservationCacheBypassTest` (PR5 Phase A-2 답습 — cache stale 박제 + DB 최신 → reserve CONFLICT throw 어설션).

새 결제 / 환불 / 재고 변경 Facade 가 *cache 응답을 if 조건 / 분기 결정* 에 사용하면 P1. `@Transactional` 안에서 *cache get → 분기* 패턴 grep — `cacheStore.get(...)` / `availabilityCacheStore.loadForRange` 가 *읽기 흐름 (`PropertyFacade`)* 외 위치에 등장하면 D-5 contract 위반 의심.

**TX commit 후 evict 강제 (`afterCommit`)** — cache evict 는 *TransactionSynchronizationManager.registerSynchronization(afterCommit)* 안에서만. TX 안 직접 evict 는 *race window* — evict 후 DB rollback 시 *cache 비어있음 + DB 옛 상태* → 다음 read 가 옛 값을 cache 재진입 → stale. 답습 위치: `WishlistFacade.wish/unwish` / `ReservationFacade.reserve/cancel` (PR4 A-3 / A-5a).

새 mutation Facade 가 cache evict 호출 시 *afterCommit 분기 미적용* + *TX 없음 시 즉시 evict 분기 미적용* 패턴이면 P1. `Grep "cacheStore\.evict|CacheStore\.evict" glob="**/*Facade.kt"` → evict 호출 시 `TransactionSynchronizationManager.registerSynchronization` 가 *같은 메서드* 에 등장하는지 짝 검증.

## 외부 입력 ↔ 내부 매핑 (Whitelisting)

도메인 어휘 (정렬 키, 필터 키, 카테고리) 가 인프라 / SQL 식별자로 변환될 때 화이트리스트 없으면 잘못된 path / SQL / 500. `map[key] ?: key` 형태의 fallback, `@Embedded` VO 정렬 키에 `vo.value` 누락, enum 변환 `valueOf` 직접 사용, **외부 입력 silent ignore** (`PageQuery.sort` 받아놓고 항상 고정 정렬) 는 모두 지적.

화이트리스트 + BAD_REQUEST 거절. `@Embedded` VO 의 정렬 경로는 `vo.field` 명시. enum 변환은 try/catch + BAD_REQUEST. 운영 / InMemory 양쪽 동일 정책.

**`set(A) == set(B)` 단독 비교** 는 list 의 중복을 못 잡는다 — 1:1 매칭이 본질이면 `size + set` 페어 가드. **외부 주입 컬렉션** 은 mutate *이전* 에 Aggregate Root 식별자와 일치하는지 가드 (`inventory.roomTypeId == reservation.roomTypeId`).

## 보안

시크릿 / API 키 / DB 비밀번호 평문 커밋, 사용자 입력을 SQL native query 에 문자열 결합 (SQL injection), 로그에 PII (전화번호 / 이메일 / 비밀번호) 그대로 출력, `X-Loopers-LoginId` 헤더 검증 없이 신뢰, 본인 자원 검증을 도메인 안에 둠, Path Traversal, OWASP Mass Assignment (`@RequestBody` 가 도메인 모델 직접 받음) 은 모두 지적. 시크릿 = 환경변수, 본인 자원 검증 = Application Layer, SQL = `@Query` + `@Param`.
