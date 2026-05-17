---
name: verify-code-parity
description: |
  verify-code orchestrator 의 **Phase 3 sub-skill** — 정합 / 일관성 결함.
  컬렉션·페이지 결정성 / DRY / Read-then-Write SELECT 중복 / 도메인 어휘 / 운영-테스트 더블 동치 / KDoc·DisplayName ↔ 실 코드 정합 (부정 약속 포함).
user-invocable: true
---

# Phase 3 — Parity

서로 다른 진실 원천끼리의 정합을 본다 — 운영 ↔ 테스트 더블, KDoc ↔ 코드, DisplayName ↔ 어설션.

## 컬렉션 / 페이지 결정성

자식 컬렉션 / 페이징 응답이 DB 재조회 후에도 같은 순서인지 본다. `@OneToMany` 에 `displayOrder` 가 있는데 `@OrderBy` / `@OrderColumn` 누락, `findAll()` / `findByX()` 가 정렬 없이 응답, 페이지 응답의 `total` 을 `List.size` 로 잘못 사용, 순서 의미 있는 컬렉션을 `Set` / `HashMap` 으로 표현하는 패턴은 모두 지적.

**단일 키 `@OrderBy` 는 항상 `id` 같은 안정적 tie-breaker 와 함께** — `@OrderBy("displayOrder ASC, id ASC")`. "displayOrder 가 unique 다" 는 DB 제약이 없으면 깨질 약속.

## 코드 중복 (DRY)

같은 검증 로직이 여러 VO 에 중복, Converter 들이 동일 ObjectMapper 초기화 반복, Facade 들이 같은 인가 검증 (loginId 일치) 반복, Repository 구현이 비슷한 변환 로직 반복하면 3회 이상시 추출 권한다. 단, 무리한 추상화는 더 큰 비용이라 *의도가 다른 중복* 은 그대로 둔다.

## Read-then-Write SELECT 중복

`existsById → deleteById`, `existsById → save`, `findById → save` 등 동일 키 select 가 두 번 발생하는 패턴. `deleteById` 자체가 내부적으로 select 후 delete — 외부에서 `existsById` 추가하면 SELECT 2회. silent noop 만 필요하면 `findById(key).ifPresent { delete(it) }` (1회) 또는 `@Modifying @Query("DELETE ...")` (0회 SELECT). 가드의 목적이 멱등 / 부재 허용이면 단일 쿼리로 통합한다. 루프 안 Repository 호출 (N+1) 은 `findAllByXIn(...)` 시그니처로 평탄화.

## 도메인 어휘 (Ubiquitous Language)

코드의 식별자가 `01-requirements.md §1` 의 어휘와 어긋나면 (`Hotel` vs `Property` 혼용), ENUM 값이 비즈니스 어휘가 아닌 기술 약자 (`STATE_1`, `T2`), 같은 개념이 여러 이름 (`user` / `member` / `account`), 영문/한글 혼용 (`getUserMok`) 패턴은 일관성 위반.

## 운영 ↔ InMemory 더블 동치 (Production-Test Parity)

테스트용 InMemory 구현은 운영 Repository 와 *동일한 의미론* 을 따라야 한다. 갈리면 회귀가 마스킹된다.

운영은 다중 키 정렬 지원하는데 더블은 첫 키만 적용, 운영은 정렬 키 화이트리스트로 거절하는데 더블은 fallback 으로 통과 (Copilot #8 함정), 운영은 빈 컬렉션 short-circuit 인데 더블은 일반 경로, 운영의 페이지네이션 경계가 더블에서 다르게, 운영의 예외 정책 (`BAD_REQUEST` vs `INTERNAL_ERROR`) 이 더블에서 다른 ErrorType — 모두 회귀 사각지대.

본 phase 의 점검: 운영 RepositoryImpl 의 search / find* 메서드 본문 ↔ InMemory 더블의 동일 메서드 본문을 *분기 의미론* (정렬 / 화이트리스트 / null 정책 / 예외 매핑) 한 표로 정리. 한 칸이라도 다르면 P1. "테스트 더블이라 단순화" 는 결함 아니라 *회귀 사각지대* 다.

## KDoc / 주석 ↔ 실제 가드·구현

KDoc 의 강한 약속 (`차단한다` / `보장한다` / `방지한다` / `불가능`) 과 실제 가드가 불일치하거나, **부정 약속** (`필요 없다` / `보장하지 않는다` / `막지 않는다` / `영향 없다` / `변환 없이`) 과 실제 동작이 어긋나거나 (긍정 약속만큼 강한 클레임 — PR9 Copilot #2), 메서드 KDoc 의 *흐름 설명* (numbered steps) 과 실제 호출 순서·시그니처 불일치 (batch 인 줄 알았는데 N+1), KDoc 의 미완성 문장 ("Property 단위 대표가" 뒤가 잘림), "고정" / "비어있어야 함" 같은 정책 약속이 진입점 가드 부재 — 모두 *문서 거짓말*.

KDoc 가 `NOT_FOUND` / `INTERNAL_ERROR` 같은 에러 정책을 약속하면 `mapNotNull` / `?.let` / `try { } catch { return ... }` 같은 silent skip 은 위반. KDoc 의 트랜잭션·락·재시도 약속이 실제 어노테이션과 불일치 ("낙관적 락" 인데 `@Version` 없음 / "재시도 3회" 인데 `@Retryable` 없음) 도 동일. KDoc 의 외부 문서 §번호 인용은 실제 문서에 그 §이 있는지 확인.

`Grep "차단|보장|방지|불가능|고정|비어있어야|반드시" path=apps/.../main glob="*.kt"` 로 강한 약속 매치 후 같은 메서드 본문의 대응 가드 대조. `Grep "필요 없다|보장하지 않는다|막지 않는다|영향 없다|변환 없이"` 로 부정 약속을 실 모델 / 구현체와 1:1 대조.

## `@DisplayName` ↔ 실제 검증 범위

DisplayName 이 "공백이거나 100자 초과면" 인데 `@ValueSource` 는 공백만 (100자 미검증), "정상/실패 모두" 인데 정상만, 한 `@Test` 안에서 두 가지 동작 검증인데 이름은 한 가지만 (given/when/then 분해 신호), 한국어 자연어와 코드 동작이 시제·주체 어긋남, **"INTERNAL_ERROR 거절" 인데 어설션이 `instanceof CoreException` 만** (ErrorType 정책 회귀 silent), `assertThatThrownBy` 블록 여러 개의 어설션 강도 비대칭 — 모두 명세 신뢰 저하.

**DisplayName 에 정렬·순서 단어** ("DESC" / "ASC" / "최신순") 가 들어가는데 어설션이 `containsExactlyInAnyOrder` / `hasSize` 로 순서 미검증이면, seed 동률이 본질이면 *DisplayName 을 완화* 하거나 *seed 시각 분리해 진짜 순서 검증* — 둘 중 하나로 정합. **DisplayName 에 cardinality** ("N건 반환" / "단건" / "빈 리스트") 면 어설션도 `hasSize` 까지. **DisplayName 에 인가 정책** ("본인 자원만 조회" / "FORBIDDEN") 면 어설션도 ErrorType 까지.

**동시성 E2E 어설션이 *상태 정합* (성공 카운트 / DB 값) 만 보고 *메시지 일관성* 미검증** 이면 CE-3 회귀 가드 부재. `failures.map { (it as CoreException).customMessage }.toSet().size <= 1` 어설션 추가.

`Grep "containsExactlyInAnyOrder" path=test glob="*Test.kt"` → 같은 테스트의 `@DisplayName` 에 "정렬|순서|DESC|ASC|최신순" 단어 있는지 교차.

## 서로 다른 진실 원천 간 충돌

Repository 인터페이스 KDoc 에 "BAD_REQUEST 거절" 명시인데 그 위 Facade KDoc 은 "고정" 만 적고 거절 동작 침묵, 운영 RepositoryImpl KDoc 과 InMemory 더블 KDoc 의 정책 표현이 다름, `.github/instructions/*.md` 룰과 KDoc 약속이 모순 — 모두 호출자가 *어느 쪽을 믿어야 할지 불명확* 한 신호. 양쪽 동기화.
