---
name: verify-code-cold-eye
description: |
  verify-code orchestrator 의 **Phase 4 sub-skill** — 최종 냉정 게이트.
  CE-1 ~ CE-6 6 항목 통과 어셔런스 + P2 보류 13 패턴 재의심 + 회귀 방지 테스트 권고.
  Phase 1~3 의 표면 grep 통과 = 결함 0 의미 아님 — *어설션 사각지대 / 분포 시나리오 / 박제-코드 거짓말* 을 다시 본다.
user-invocable: true
---

# Phase 4 — Cold-Eye

매 사이클 6 항목 모두 ✅ 어셔런스가 없으면 orchestrator 가 PASS 금지. *"어설션이 통과한다 = 결함 없음"* 으로 결론 내지 않는다 — 어설션이 검증하지 않는 사각지대가 본질적 위험.

## CE-1. try/catch 의 catch scope 정합

Facade 가 `try { repo.save(model) } catch (e: JpaException) { ... }` 패턴을 쓸 때, **try 블록 밖에서 같은 도메인 사고의 다른 throw 경로** 가 있는지 추론한다. `model.someMethod()` 호출이 try *위* 라인에 있고 도메인 검증 throw 가 catch 밖으로 흘러 *다른 메시지* 로 응답하면 클라이언트 일관성 깨짐. try 블록은 그 도메인 사고의 *모든 호출* 을 감싸고, `catch (e: CoreException) { if (e.errorType == 동일 ErrorType) throw CoreException(..., cause = e); else throw e }` 로 errorType 별 메시지 정규화.

`Grep "try \{" path=apps/.../application glob="*Facade.kt"` 후 각 try 블록 위 라인까지 거슬러 throw 가능 위치 시그니처 추적.

## CE-2. 분포 시나리오 추론

동시성 자원의 트랜잭션 흐름은 *REPEATABLE_READ snapshot 분포* 에 따라 같은 의도의 요청이 다른 throw 경로로 갈린다. *분포 1* (TX_B 가 TX_A commit 이전 시작 → snapshot 에 변경 전 상태 → memory 검증 통과 → commit 시점에 stale version → JPA 예외) 와 *분포 2* (TX_B 가 TX_A commit 이후 시작 → snapshot 에 변경 후 상태 → memory 검증 실패 → 도메인 throw) 양쪽에서 *동일 메시지* 응답을 강제한다. ConcurrentXxxTest 는 deterministic latch 라 분포 1 에 치우치므로 *분포 2 가 발동되는 시나리오* (사전 commit + `Thread.sleep`) 도 의식적으로 추가하거나 영구 한계로 박제.

## CE-3. 어설션 강도

DisplayName 의 모든 동사·정책 단어가 어설션으로 1:1 검증되는지 본다. "정확히 N건 성공" → `isEqualTo(N)`, "M건 CONFLICT" → 카운트 + **`failures.map { customMessage }.toSet().size <= 1` 메시지 일관성**, "raw 예외 0" → `others.isZero()`. 동시성 E2E 어설션이 *상태 정합* (성공 카운트 / DB 값) 만 보고 *메시지 일관성* 미검증이면 CE-1 의 회귀 가드가 부재 — 격상.

## CE-4. 3축 정합 (DB / 도메인 메모리 / Facade 응답)

운영 코드의 한 도메인 사고는 (1) DB 레벨 — UNIQUE 제약 / `@Version` / `WHERE x > 0` 가드, (2) 도메인 메모리 레벨 — `canTransitTo(...)` / `init { require(...) }` / `requireOwner(...)`, (3) Facade 응답 레벨 — try/catch 변환 + 메시지 일반화 — 3축 모두에서 *같은 ErrorType + 같은 customMessage* 로 응답되어야 한다. 한 축만 막고 다른 축은 다른 메시지면 분포 시나리오에서 회귀.

## CE-5. 3 회 더 의심

사이클을 끝내기 *전에* 다음 3 질문을 한 번 더 한다. 한 항목이라도 *불확실* 하면 fix 권고로 박제.

- 이 PR 의 어설션 100% 가 통과한다고 *진짜로* 동시성 사고 0 인가? — 어설션이 검증하지 않는 사각지대 (메시지 / stale snapshot / cache staleness) 가 있는가?
- 이 catch 블록이 잡지 못하는 throw 경로가 있는가? — 같은 try 안의 *그 위 라인* 까지 거슬러 throw 가능 위치 추적.
- 이 KDoc 의 강한 약속 (`차단` / `보장` / `방지` / `불가능`) 이 *실제 코드* 와 정합한가?

## CE-6. PR 박제 ↔ 실 코드 정합

박제 문서 (pr.md / experiments-results.md / decision.md) 가 *미해결 위험* 으로 적은 항목이 실제로는 코드에 가드가 있거나, *해결됐다고 적은 항목* 이 실제로는 race window 가 남아있으면 *문서 거짓말* 의 PR 박제 버전. 박제 문서의 "미해결 위험" / "트레이드오프" 섹션을 *코드 본문 grep* 으로 1:1 검증. 어긋나면 박제 갱신 또는 코드 fix 둘 중 하나.

## 우선순위 (P0/P1/P2)

분량이 많기 때문에 모든 항목을 동등하게 다루지 않는다.

- **P0 (반드시 차단)** — Null 일관성 / 자식 Entity ID / 외부 라이브러리 누출 / 입력 검증 / 트랜잭션 / 보안
- **P1 (강한 권고)** — 캐시 SSOT / 멱등 / 동시성 / 시간 / 예외 (특히 메시지 식별자 노출) / 가시성 / 외부 입력 매핑 (silent ignore 포함) / 성능 (Read-then-Write SELECT 중복 / `@Table(indexes=...)` PK 중복) / 문서-동작 정합
- **P2 (권고)** — 결정성 (tie-breaker) / 사일런트 디폴트 / 자원 / 매직 상수 / DRY / 어휘

P0 위반 1건도 FAIL. P1 / P2 는 누적 정도와 영향 범위로 판단.

## P2 보류 13 패턴 — Cycle 2 자체 회귀에서 재의심 (외부 리뷰가 P1+ 로 잡는 함정)

Cycle 1 에서 P2 로 분류해 보류한 항목 중 다음 13 패턴은 외부 리뷰가 P1 이상으로 잡는다. **Cycle 2 자체 회귀에서 반드시 한 번 더 의심** — 매치되면 명시적 사유 ("4주차 동시성 영역" / "별도 인프라 라운드" 같은 일반화 가능한 이유) 가 PR 본문에 박혀있지 않으면 P1 으로 격상 후 orchestrator 에 FAIL 보고.

1. 예외 메시지의 외부 식별자 (LoginId / email / userId / token) — 보안 / 응답 일관성.
2. Read-then-Write 의 SELECT 중복 (`existsBy → delete`, `findById → save`) — 운영 부하.
3. 외부 입력의 silent ignore (`PageQuery.sort` 무시) — 호출자 오용 가림.
4. 운영-테스트 더블 정책 비대칭 — 회귀 사각지대.
5. JPA 컬럼 중복 매핑 (`@Embedded VO @Column(name="X")` + 외부 entity 의 `@Column(name="X")`) — 단위 테스트 통과해도 풀 컨텍스트 / `@DataJpaTest` 시점 폭발 P0 격상.
6. 부수효과 이전 cheap input guard 누락 — Strong Exception Safety service-level.
7. 외부 주입 컬렉션 ↔ Aggregate 식별자 가드 부재 — 호출자 실수로 다른 자원 mutate.
8. `set(A) == set(B)` 단독 비교의 중복 사각지대 — 1:1 매칭이면 `size + set` 페어.
9. 다일자 비관적 락의 `ORDER BY` 미명시 — 데드락.
10. JPA 예외 (`OptimisticLockingFailureException` 등) 가 클라이언트 응답 노출 — 식별자 노출 + 도메인 의미 부재.
11. `@Lock(PESSIMISTIC_WRITE)` / `saveAndFlush` 가 `@Transactional` 밖 호출.
12. Facade try/catch 의 catch scope 가 모든 throw 경로를 감싸지 않음 (CE-1 / §12).
13. 동시성 E2E 어설션이 상태 정합만 보고 메시지 일관성 미검증 (CE-3).

## 회귀 방지 테스트 권고

각 항목에서 *위반이 발견되면* 보완 코드와 함께 회귀 방지 테스트도 권고한다. verify-tests 게이트가 그 테스트를 강제 — 두 게이트는 한 쌍. 예: "갤러리에 없는 URL 으로 `replaceMainImage` 호출 시 BAD_REQUEST + 상태 불변", "신규 Property 에 자식 추가 후 저장/재조회 시 자식의 `property_id` 가 부모 id 와 동일", "Converter.convertToDatabaseColumn(null) 호출 시 INTERNAL_ERROR", "다중 sort key 적용 — 1차 키 동률일 때 2차 키로 정렬" 운영/InMemory 동치 테스트, "동시성 E2E 의 `failures.map { customMessage }.toSet()` 크기 ≤ 1" 메시지 일관성.

## 출력 (orchestrator 통합용)

CE-1~CE-6 6 항목 통과 여부 표 + P2 보류 13 패턴 재의심 결과 표 + 최종 P0/P1/P2 통합 + PASS/FAIL 후보 박제. 6/6 ✅ 어셔런스 없으면 명시적으로 FAIL 보고.
