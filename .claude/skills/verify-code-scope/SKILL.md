---
name: verify-code-scope
description: |
  verify-code orchestrator 의 **Phase 0 sub-skill** — 변경 scope 확정 / 카테고리 매핑 / `.github/instructions/*.md` 활성화.
  후속 Phase 1~4 가 *어떤 룰을 봐야 하는지* 의 입력을 만든다. 단독 호출은 *변경 진단* 용도로 가능.
user-invocable: true
---

# Phase 0 — Scope & Mapping

본 sub-skill 은 PASS/FAIL 판정을 내리지 않는다 — 후속 phase 의 입력을 만드는 데만 책임이 있다.

## Scope 확정

`git diff origin/main...HEAD --name-only` 로 PR 전체 변경 영역을 잡는다. 직전 라운드 fix 한 파일만 다시 보면 *기존* 파일의 동일 패턴 결함을 놓친다 — Copilot 은 매 리뷰마다 PR 전체를 보므로, 본 게이트도 그래야 한다.

출력에 반드시 박제: (1) 변경 파일 수 + 카테고리 (코드 / 테스트 / 마이그레이션 / 문서 / 설정 / 스킬), (2) 신규 외부 라이브러리·외부 호출 도입 여부, (3) DB 스키마 변경 (NOT NULL / UNIQUE / FK / 인덱스), (4) 변경이 *동시성 / 트랜잭션 / 예외 흐름* 영역인지 — Yes 면 Phase 4 cold-eye 의 CE-1/CE-2 가 명시 강화 발동.

## 변경 카테고리 → 필수 룰 매핑

매 사이클 출력에 `[카테고리명] → [위임 Phase] → [표준 grep 결과 요약]` 표를 반드시 포함한다. 카테고리에 매치되었는데 grep 미실행이면 orchestrator 가 PASS 금지.

- **VO `init { }` 변경** (`domain/**/*Model.kt` 또는 `value/*.kt` + `init {` / `require(`) → Phase 1 (§6 입력 검증 / §13 매직 상수) + Phase 3 (§19-B). `Grep "init \{\|require\("` 로 가드 기준 (음수 / 0 / 빈) 이 비즈니스 의미와 정합인지 대조. *0 이 의미 없는데 통과* 패턴은 P1.
- **Repository 인터페이스 KDoc 변경** (`domain/**/Repository*.kt`) → Phase 1 (§3) + Phase 3 (§19-B 부정 약속). `Grep "필요 없다\|보장하지 않는다\|막지 않는다\|영향 없다\|변환 없이"` 로 부정 약속을 *실 구현체 동작* 과 1:1 대조.
- **Facade `try { } catch` 변경** (`application/**/*Facade.kt`) → Phase 2 (§11/§12) + Phase 4 (CE-1). 각 try 블록 *위 라인* 까지 거슬러 도메인 메서드 throw 가능 위치 추적 + catch 메시지가 같은 errorType 내 단일 customMessage 인지 확인.
- **동시성 E2E 테스트 추가/변경** (`test/**/*Concurrent*Test.kt`) → Phase 2 (§9/§11) + Phase 4 (CE-3) + Phase 3 (§19-B). 모든 latch await 의 Boolean 반환이 `check(...)` 로 감싸였는지 + catch 가 *카테고리 한정* 인지 + customMessage 일관성 어설션 존재 여부.
- **`@Column(length = N)` / `@Column(nullable = ...)` 변경** → Phase 1 (§1/§6). `Grep "@Column.*length\s*=\s*\d+"` 로 같은 파일 `length >` 가드 존재 + 두 N 이 동일 `companion const val` 로 묶였는지 확인.
- **비관적 락 / `saveAndFlush` / `@Version` 추가** → Phase 2 (§9/§16) + Phase 3 (§19-B). 호출 측 Facade 의 `@Transactional` 정합 + ORDER BY / 진입점 정렬 양쪽 박제.
- **Converter 변경** (`infrastructure/**/converter/*.kt`) → Phase 1 (§1) + Phase 2 (§11/§12). 두 메서드의 try/catch 구조 *대칭* + cause 보존 + 로그 raw payload 길이 제한.
- **`@Embeddable VO` / `@Embedded` 변경** → Phase 1 (§6/§3). 같은 entity 내 동일 `@Column(name="...")` 중복 매핑 검출 (Hibernate bootstrap 폭발 방지).
- **InMemory 더블 변경** (`support/test/InMemory*Repository.kt`) → Phase 3 (§19-A). 운영 RepositoryImpl 과의 *분기 의미론* (정렬 / 화이트리스트 / null 정책 / 예외 매핑) 을 한 표로 정리. 한 칸이라도 다르면 P1.
- **DTO ↔ 도메인 매핑 변경** (`*V1Dto.kt`) → Phase 1 (§3/§15) + Phase 3 (§19). 도메인 모델의 모든 *입력 가능 필드* 가 매핑되는지 + 도메인 어휘와 일치.
- **QueryDSL projection 추가** (`*Row.kt`, `Projections.constructor`, multi-column `select()`) → Phase 1 (§3) + Phase 3 (§19-B). projection 이 plain Kotlin 인지 + KDoc numbered flow 가 실 호출 순서와 1:1.
- **마이그레이션 SQL (`V*.sql`)** → Phase 1 (§1/§6) + Phase 3 (§19-B). 컬럼이 도메인 `@Column` 과 정합 + 인덱스가 PK 와 중복 아닌지.

카테고리 매핑이 0건이면 (순수 문서 / `.claude/skills/**` / `docs/**` 변경) verify-code 전체를 *명시 N/A* 로 orchestrator 에 보고. 침묵 금지.

## `.github/instructions/*.md` 활성화

각 instruction 의 `applyTo` 글롭과 변경 파일 경로를 매치해 활성화 — 매치 안 되면 N/A. 활성화된 instruction 은 *Copilot 동일 기준* — Phase 1~4 sub-skill 이 본 스킬 룰과 동시에 검증한다.

- `domain/**/*.kt` → `domain.instructions.md` + `kotlin.instructions.md`
- `infrastructure/**/*.kt`, `*JpaRepository*`, `*RepositoryImpl*` → `repository.instructions.md` + `kotlin.instructions.md`
- `application/**/*.kt`, `*Facade*` → `service.instructions.md` + `kotlin.instructions.md`
- `interfaces/api/**/*.kt` → `controller.instructions.md` + `kotlin.instructions.md`
- `test/**/*.kt`, `*Test*` → `test.instructions.md` + `kotlin.instructions.md`
- `build.gradle*` → `gradle.instructions.md`
- `application*.yml` → `spring-config.instructions.md`

각 instruction 의 *수용된 트레이드오프* 섹션 (`var ... protected set` 패턴, 테스트 fake reflection 격리 등) 은 Cycle 2 자체 회귀에서 무심코 깨뜨리지 않게 재확인한다.
