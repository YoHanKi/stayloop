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

자동 호출 순서: **`verify-code` → `verify-architecture` → `verify-tests`**. 앞 단계가 `FAIL` 이면 다음 단계는 의미가 없으므로, 코드 본문 결함 → 구조 결함 → 테스트 누락 순으로 해소한다.

| 스킬 | 용도 | 호출 시점 |
|---|---|---|
| `verify-code` | 불변식 / null 일관성 / 자식 entity ID 동기화 / 외부 라이브러리 누출 / 컬렉션 정렬 / 캐시 SSOT / 동시성 / 자원 / Clock / 예외 / 가시성 / 트랜잭션 / 보안 등 **코드 본문**의 사일런트 결함 점검 | **기능 구현/변경 직후 가장 먼저.** Copilot/시니어 리뷰가 잡는 패턴을 사전에 차단. |
| `verify-architecture` | 계층 의존 방향, Aggregate 패키지·명명 규약, 트랜잭션 경계, Repository 위치, DTO/JPA 어노테이션 누출, 멀티모듈 경계 점검 | **`verify-code` 가 PASS 된 직후.** 결과가 `FAIL` 인 동안 테스트 게이트로 넘어가지 않는다. |
| `verify-tests` | 단위/통합/E2E 테스트 충분성·구조·더블·실패 시나리오 점검 + `./gradlew ktlintCheck && test` 강제 실행 | **`verify-architecture` 가 PASS 된 직후, 사용자에게 작업 완료를 보고하기 전에 반드시 호출.** 결과가 `FAIL` 인 동안 작업은 미완료. |

예외: 사용자가 명시적으로 스킵을 지시했거나, 변경이 순수 문서·메타 파일에 한정된 경우.

수동 호출 스킬: 아래는 자동 호출하지 않으며, 사용자가 명시적으로 요청할 때만 사용한다.

| 스킬 | 용도 | 호출 시점 |
|---|---|---|
| `create-plan` | 주차 plan 의 *사고 경로 SSOT* `docs/plan/week{n}-b.md` (Loop-driven, 막연함 → 부딪힘 → 고민 → 결과) 작성. 짝 closed plan (`docs/plan/week{n}.md`) 와의 매핑 검증. 정형 예시 = `docs/plan/week5-b.md`. | **새 주차 진입 직전 / 기존 closed plan 의 짝 b.md 보완 시 명시 호출.** 자동 게이트 X. |
| `create-pr` | 브랜치 컨벤션·커밋 prefix·`documents/feature/{topic}/pr.md` 골격·푸시·PR URL 산출 보조 | **사용자가 "PR 만들어줘" 등으로 명시적으로 요청할 때만 호출.** 자동 게이트로 사용하지 않는다. |
| `record-decision` | 주차 plan 진행 중 내려진 *주요 의사결정* (대안 비교 / 선택 / 근거 / 트레이드오프) 을 `docs/plan/week{n}/decision.md` 에 누적 박제. | **사용자가 "결정 기록해줘" / "박제해줘" 등으로 명시적으로 요청할 때만 호출.** 자동 게이트 X. |
| `experiment-recurse` | Testcontainers / k6 실험 결과 박제를 **재귀로 (최소 1회, 최대 3회)** 검토 — 가설 ↔ 측정 사이의 환경 / 시나리오 / 도구 의미론 / 라벨링 / 해석 / 통계적 유의성 / 가설 자체 의 7 축 허점 분류 (a 즉시 수정 / b 재실험 / c 영구 한계). | **실험 박제 작성 직후 *최소 1회*. 사용자가 "실험 검토해줘" / "허점 찾아줘" 등으로 명시 호출.** 자동 게이트 X. |

## Plan 구조 (주차)

주차 plan 은 **두 짝 문서** 로 구성한다 — 정형 예시는 `docs/plan/week5-b.md` + `docs/plan/week5.md`:

| 문서 | 위상 | SSOT 영역 | 작성 시점 |
|---|---|---|---|
| `docs/plan/week{n}-b.md` | *Loop-driven* (사고 경로) | *어떻게 발견했는가* — 막연함 → 시도 → 부딪힘 → 고민 → 결과 → ➡️ 매핑 | 주차 진입 직전 ~ Loop 발견 시점마다 누적 |
| `docs/plan/week{n}.md` | *Closed* (결과) | *무엇을 할 것인가* — PR / Phase / 박제 형식 / 반증 가드 임계 | b.md 의 Loop 결과가 *형식* 으로 굳을 때 |
| `docs/plan/week{n}/decision.md` | *Decision log* | *왜 그렇게 결정했는가* — 대안 비교 / 트레이드오프 / 미래 재검토 | 주요 결정 시점마다 누적 (`record-decision`) |

**규약**:
- b.md 의 어떤 Loop 도 closed plan 의 *PR / D-N / Phase* 에 매핑된다. 매핑이 깨지면 *b.md 가 허공* 이거나 *closed plan 미갱신*.
- b.md 는 *추가 사실을 박지 않는다* — closed plan 이 내용 SSOT, b.md 는 경로 박제.
- b.md 의 본문 Loop 는 *발견 시점의 박제* — 후행 변경 금지 (closed plan 갱신 시에도 매핑 표만 갱신).
- 주차 종결 시 두 문서 모두 read-only.

**작성 흐름**: `create-plan` (b.md 작성) → 사용자 / 별도 작업 (closed plan 작성) → `record-decision` (결정 박제, 누적).

## 실험 테스트 (Experiment Tests)

트레이드오프 결정 (락 전략 / timeout / JPA 옵션 / 격리 수준 등) 은 *비즈니스 직관* 만으로 박제하지 않는다. **Testcontainers 통합 테스트** 또는 **k6 부하 실험** 으로 가설을 *반증 가능* 하게 측정한 뒤 결정한다.

**워크플로우 (6 step)** — 1 가설 → 2 설계 → 3 실행 → 4 박제 → **5 재귀 검토 (`experiment-recurse`)** → 6 코드 삭제. 단계별 상세 기준 / 호출 시점 / 박제 위치 / 7 축 허점 분류는 다음을 참조한다.

- 일반 테스트 ↔ 실험 테스트 분류, 위치 정책, Skip 조건, `record-decision` 과의 관계 — `.claude/skills/experiment-recurse/SKILL.md` (재귀 검토 본 스킬이 호출되기 *전제* 가 되는 박제 형식 / 위치 정책 모두 본 스킬 KDoc 에 정렬).
- 측정 코드는 `**/src/test/kotlin/**/experiment/` (`.gitignore`) 또는 `k6/local/` (`.gitignore`) — 박제된 결과가 SSOT, 코드는 일회성 도구.
- 박제 위치는 `docs/plan/week{n}/decision.md` 의 D-N *실험 검증* 절 또는 `documents/feature/{topic}/experiments-results.md` (git 추적 가시화 박제) 중 선택.

**Skip 가능**:
- 외부 근거가 *표준화* 된 항목 (예: MySQL 공식 문서가 단정 답하는 항목).
- 변경 리스크가 *시각적으로 명백* 한 typo 수정.
- *비즈니스 결정* 이 본질인 항목 (쿠폰 복원 / 다국적 통화 등 — 측정 항목 X).

## 도메인 / 아키텍처

세부 규칙은 본 파일에 중복 기재하지 않는다. 새 Aggregate 를 만들거나 기존 구조를 검수할 때 다음을 참조한다.

- **표준 Aggregate 패턴 (Model / Service / Facade / V1Dto / JpaRepository + RepositoryImpl)** — `documents/feature/user-architecture/pr.md`. 회원 도메인이 정렬한 구조를 그대로 답습한다.
- **Entity / VO / Domain Service 구분, 레이어드 + DIP 원칙** — `docs/presentation/week3.md`.
- **검수 규칙 (의존 방향, 명명, 트랜잭션 경계, JPA 어노테이션 누출 등)** — `.claude/skills/verify-architecture/SKILL.md`. 위반 여부는 본 스킬을 호출해 판정한다.

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우(= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.

### Md 박제 파일은 commit 금지 (사용자 로컬 검토용)

Claude 는 *코드 / 테스트 / 마이그레이션 SQL* 만 자동으로 stage / commit 한다. 아래 *박제용 md 파일* 들은 **사용자가 직접 검토 후 본인 시점에 commit 할 로컬 산출물** 이라 Claude 가 `git add` / `git commit` 대상으로 삼지 않는다 (`-f` 강제 추가도 금지 — 사용자 명시 요청 시만).

**대상**:
- `docs/plan/week{n}-b.md` (Loop walkthrough)
- `docs/plan/week{n}/decision.md` (decision log, `record-decision` 산출물)
- `documents/feature/{topic}/comparison.md` (실험 비교 매트릭스)
- `documents/feature/{topic}/k6-results.md` (k6 측정 박제)
- `documents/feature/{topic}/seed-load.md` / `experiments-results.md` 등 기타 박제용 md

**예외**: 사용자가 *명시적으로* "decision.md 커밋해줘" / "comparison.md 도 같이 stage" 등으로 요청한 경우만 stage / commit.

**왜**: 박제 md 는 사용자의 *검토 사이클* 산물 — Claude 가 미리 commit 하면 (a) 검토 전 history 에 들어가고 (b) 사용자가 본인 흐름에 맞게 묶어 push / PR 하는 결정권을 침해. PR1 (`feature/property-search-perf-index`) 까지는 commit 했으나 PR2 부터 정책 변경.

**Stage 흐름**:
- ✅ 코드 / 테스트 변경 → `git add <code>` → commit
- ✅ 마이그레이션 SQL (`db/migration/V*.sql`) → `git add` → commit
- ❌ 박제 md → 디스크에 남기되 stage X. commit message 에서 *박제 위치* path 만 참조 OK.
