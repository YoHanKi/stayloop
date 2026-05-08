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
| `create-pr` | 브랜치 컨벤션·커밋 prefix·`documents/feature/{topic}/pr.md` 골격·푸시·PR URL 산출 보조 | **사용자가 "PR 만들어줘" 등으로 명시적으로 요청할 때만 호출.** 자동 게이트로 사용하지 않는다. |
| `record-decision` | 주차 plan 진행 중 내려진 *주요 의사결정* (대안 비교 / 선택 / 근거 / 트레이드오프) 을 `docs/plan/week{n}/decision.md` 에 누적 박제. plan 본문은 *결과*, 본 스킬 산출물은 *기준* 으로 분담. | **사용자가 "결정 기록해줘" / "박제해줘" 등으로 명시적으로 요청할 때만 호출.** 자동 게이트 X. |

## 실험 테스트 (Experiment Tests)

트레이드오프 결정 (락 전략 / timeout 값 / JPA 옵션 / 격리 수준 등) 은 *비즈니스·시니어 직관* 만으로 박제하지 않는다. 가능하면 **Testcontainers 통합 테스트** 또는 **k6 부하 실험** 으로 가설을 *반증할 수 있게* 측정한 뒤 결정한다.

**실험 테스트 ↔ 일반 테스트의 차이**:

| 항목 | 일반 (기능) 테스트 | 실험 테스트 |
|---|---|---|
| 목적 | 영구 회귀 가드 (동작 계약) | 일회성 가설 검증 |
| 위치 | `src/test/kotlin/...` 일반 위치 | `**/src/test/kotlin/**/experiment/` (`.gitignore`) / `k6/local/` |
| 수명 | 영구 — git 추적 | 결과 박제 후 **삭제** |
| SSOT | 코드 자체 | `docs/plan/week{n}/decision.md` 또는 `docs/plan/week{n}/db-lock-low-level.md` 의 *실험 검증* 절 박제 |
| 자동 게이트 | `verify-tests` 가 강제 | 강제 X — 결정 직전 *수동* 으로 1회 실행 |

**워크플로우 (5 step)**:
1. **가설** (Hypothesis) — *무엇이 사실일 것 같은가* 를 한 줄로 (예: "비관적 락이 atomic UPDATE 보다 다일자 부분 실패 보상 비용 작음").
2. **실험 설계** — Testcontainers / k6 로 가설을 *반증 가능* 하게 측정. 측정 항목 (p95 latency / throughput / error rate / deadlock count 등 정량) 과 판정 임계값을 *사전* 합의.
3. **실행** — `./gradlew test --tests "*ExperimentTest"` 또는 `k6 run k6/local/<name>.js`.
4. **결과 박제** — 가설 / 측정값 / 판정 / 채택 결정을 `docs/plan/week{n}/decision.md` 의 D-N 또는 `db-lock-low-level.md` 의 LQ *실험 검증* 절에 기록 (날짜 + 환경 + 측정 raw 값 포함).
5. **삭제** — `experiment/` 디렉토리 / k6 스크립트 제거. **박제된 결과가 SSOT**, 코드는 일회성 도구.

**위치 정책 (`.gitignore` 박제)**:
- `**/src/test/kotlin/**/experiment/` — Kotlin 실험 테스트 (Testcontainers 활용).
- `k6/local/` — k6 부하 실험 (이미 박제).

**호출 금지**:
- 영구 회귀 가드용 테스트는 일반 위치. `experiment/` 에 두지 말 것 — 다음 사람이 *왜 이게 사라졌지* 로 헷갈림.
- 결과 박제 없이 *코드만 남기는* 실험 — 코드만 보고 가설 / 판정 기준 / 결과를 재구성 못 함.

**Skip 가능**:
- 결정의 외부 근거가 *표준화* 되어 있을 때 (예: MySQL 공식 문서가 단정적으로 답하는 항목 — `innodb_autoinc_lock_mode` 의 모드별 동작).
- 변경 리스크가 *시각적으로 명백* 할 때 (예: typo 수정).
- *비즈니스 결정* 이 본질일 때 (예: 쿠폰 복원 정책 — 측정 가능한 항목 X, 도메인 정책 영역).

**자동 게이트와의 관계**:
- `verify-tests` 는 *일반 테스트만* 강제. `experiment/` 디렉토리는 `.gitignore` 라 자동 검출 X.
- 실험 결과 박제는 `record-decision` 스킬과 함께 — 실험 검증 후 결정이 굳어지면 `Skill(record-decision)` 으로 `decision.md` 에 D-N 추가.

## 도메인 / 아키텍처

세부 규칙은 본 파일에 중복 기재하지 않는다. 새 Aggregate 를 만들거나 기존 구조를 검수할 때 다음을 참조한다.

- **표준 Aggregate 패턴 (Model / Service / Facade / V1Dto / JpaRepository + RepositoryImpl)** — `documents/feature/user-architecture/pr.md`. 회원 도메인이 정렬한 구조를 그대로 답습한다.
- **Entity / VO / Domain Service 구분, 레이어드 + DIP 원칙** — `docs/presentation/week3.md`.
- **검수 규칙 (의존 방향, 명명, 트랜잭션 경계, JPA 어노테이션 누출 등)** — `.claude/skills/verify-architecture/SKILL.md`. 위반 여부는 본 스킬을 호출해 판정한다.

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우(= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.
