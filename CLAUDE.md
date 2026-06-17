# CLAUDE.md

Stayloop — Spring Boot 3 + Kotlin 멀티모듈 숙박 예약 백엔드.

## 빌드 / 실행

- `make init` — pre-commit (ktlint) 설치
- `docker-compose -f ./docker/infra-compose.yml up` — 로컬 인프라
- `./gradlew :apps:stay-api:bootRun` — 앱 실행 (local)
- `./gradlew test` / `./gradlew ktlintCheck` — 검증

모듈 구조: `apps/` (실행), `modules/` (재사용 config), `supports/` (애드온). 자세한 정책은 `README.md`. 주차별 요구사항은 `docs/presentation/week*-quests.md`.

## 스킬

`.claude/skills/` 에 정의된 프로젝트 전용 스킬. `Skill(skill="<name>")` 으로 호출한다.

자동 호출 순서: **`verify-architecture` → `verify-tests`**. 앞 단계가 `FAIL` 이면 다음 단계는 의미가 없으므로, 구조 결함을 먼저 해소한 뒤 테스트 게이트로 넘어간다.

| 스킬 | 용도 | 호출 시점 |
|---|---|---|
| `verify-architecture` | 계층 의존 방향, Aggregate 패키지·명명 규약, 트랜잭션 경계, Repository 위치, DTO/JPA 어노테이션 누출, 멀티모듈 경계 점검 | **기능 구현/변경이 끝난 직후, `verify-tests` 보다 먼저 반드시 호출.** 결과가 `FAIL` 인 동안 테스트 게이트로 넘어가지 않는다. |
| `verify-tests` | 단위/통합/E2E 테스트 충분성·구조·더블·실패 시나리오 점검 + `./gradlew ktlintCheck && test` 강제 실행 | **`verify-architecture` 가 PASS 된 직후, 사용자에게 작업 완료를 보고하기 전에 반드시 호출.** 결과가 `FAIL` 인 동안 작업은 미완료. |

예외: 사용자가 명시적으로 스킵을 지시했거나, 변경이 순수 문서·메타 파일에 한정된 경우.

수동 호출 스킬: 아래는 자동 호출하지 않으며, 사용자가 명시적으로 요청할 때만 사용한다.

| 스킬 | 용도 | 호출 시점 |
|---|---|---|
| `create-pr` | 브랜치 컨벤션·커밋 prefix·`documents/feature/{topic}/pr.md` 골격·푸시·PR URL 산출 보조 | **사용자가 "PR 만들어줘" 등으로 명시적으로 요청할 때만 호출.** 자동 게이트로 사용하지 않는다. |

## 도메인 / 아키텍처

세부 규칙은 본 파일에 중복 기재하지 않는다. 새 Aggregate 를 만들거나 기존 구조를 검수할 때 다음을 참조한다.

- **표준 Aggregate 패턴 (Model / Service / Facade / V1Dto / JpaRepository + RepositoryImpl)** — `documents/feature/user-architecture/pr.md`. 회원 도메인이 정렬한 구조를 그대로 답습한다.
- **Entity / VO / Domain Service 구분, 레이어드 + DIP 원칙** — `docs/presentation/week3.md`.
- **검수 규칙 (의존 방향, 명명, 트랜잭션 경계, JPA 어노테이션 누출 등)** — `.claude/skills/verify-architecture/SKILL.md`. 위반 여부는 본 스킬을 호출해 판정한다.

### 쿼리 작성

- **커스텀 쿼리는 QueryDSL 로 작성한다.** `JpaRepository` 인터페이스는 CRUD(`save`/`findById`/`existsById`/`deleteById` 등)만 두고 `JpaRepository<T, ID>` 만 상속한다. 조회·갱신 쿼리는 `RepositoryImpl`(또는 도메인 서비스)에서 `JPAQueryFactory` 로 짠다. `@Query`/`@Modifying` 어노테이션 쿼리는 지양 — 조금이라도 난해하면 QueryDSL 로.
- **영속성 계층은 사실만 반환하고 비즈니스 판단은 도메인/애플리케이션이 한다.** 조건부 갱신의 영향 행 수 같은 값은 Repository 가 그대로 돌려주고, "0 행 = 품절 → CONFLICT" 같은 해석은 도메인 서비스/Facade 에서 명시적으로 한다. 영향 행 수 검사를 쿼리 어댑터 안에서 예외로 바꾸지 않는다(참고: `docs/decision/week4/02-querydsl-and-affected-rows.md`).

## 의사결정 기록 (필수)

구현·리팩토링 과정에서 **둘 이상의 선택지를 비교했거나, 트레이드오프를 따져 한쪽을 채택·기각했거나, 실측(부하·벤치마크)으로 판단을 내렸으면 그 결정을 반드시 문서로 남긴다.** 코드만 남기고 근거를 흘리면 "왜 이렇게 했는가" 가 사라져 다음 사람이 같은 고민을 반복한다.

- **시점**: 해당 결정이 포함된 작업을 사용자에게 완료 보고하기 **전**에 작성한다. 결정 없이 단일 자명한 구현만 있었으면 생략 가능.
- **위치**: `docs/decision/week{N}/{NN}-{topic}.md` (예: `docs/decision/week4/01-inventory-concurrency-strategy.md`). 주차 디렉터리가 없으면 만든다. `{NN}` 은 그 주차 내 결정 순번.
- **담을 것** (골격):
  1. 맥락 / 풀려는 문제 — 어떤 불변식·요구가 걸렸는가.
  2. 선택지와 고민 — 비교한 후보들을 각각 무엇을 기준으로 따졌는가.
  3. 코드 예시 — 후보별 핵심 코드/쿼리 스니펫.
  4. 실제 테스트 환경 — 어디서 어떻게 쟀는가(DB·도구·부하 모수·하드웨어 전제 등 재현 정보).
  5. 판단 기준 — 무엇을 보고 고를지 사전에 정한 척도.
  6. 결과 — 실측 수치/관찰(표로).
  7. 트레이드오프 — 채택안이 포기한 것, 기각안이 나았을 조건.
  8. 결론 — 무엇을 채택·기각했고 근거는 무엇인가. 되돌릴 트리거가 있으면 함께.
  9. 후속 / 미해결 — 이번 범위 밖으로 미룬 것.
- **commit 대상**: 결정 문서(`docs/decision/**`)는 코드와 함께 commit 한다. 단 raw 실측 산출물(k6 결과 json·md 등 로컬 검토용)은 commit 하지 않고, 문서에는 요약 수치만 옮긴다.

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우(= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.
