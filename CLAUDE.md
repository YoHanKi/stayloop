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

자동 호출 순서: **`verify-code` → `verify-architecture` → `verify-tests`**. 앞 단계가 `FAIL` 이면 다음은 의미 없다.

| 스킬 | 용도 |
|---|---|
| `verify-code` | **오케스트레이터** — 5 sub-skill (`-scope` / `-invariants` / `-flow` / `-parity` / `-cold-eye`) 을 *무조건 2 사이클 이상* 실행. 기본 = 냉정 + 검증자 + 꼼꼼 모드. 상세는 본 스킬 SKILL.md 참조. |
| `verify-architecture` | 계층 의존 방향, Aggregate 패키지·명명 규약, 트랜잭션 경계, Repository 위치, DTO/JPA 어노테이션 누출, 멀티모듈 경계 |
| `verify-tests` | 단위/통합/E2E 테스트 충분성·구조·더블·실패 시나리오 + `./gradlew ktlintCheck && test` 강제 실행 |

예외: 사용자가 명시적으로 스킵을 지시했거나, 변경이 순수 문서·메타 파일에 한정된 경우.

수동 호출 스킬 (사용자 명시 요청 시만):

| 스킬 | 용도 |
|---|---|
| `create-plan` | 주차 plan 의 *사고 경로 SSOT* `docs/plan/week{n}-b.md` 작성 + closed plan 매핑 검증. |
| `create-pr` | 브랜치 컨벤션·커밋 prefix·`documents/feature/{topic}/pr.md` 골격·푸시·PR URL 산출. |
| `record-decision` | 주요 의사결정 (대안 비교 / 트레이드오프) 을 `docs/plan/week{n}/decision.md` 에 누적 박제. |
| `experiment-recurse` | Testcontainers / k6 실험 결과 박제를 재귀 검토 — 7 축 허점 분류. 박제 작성 직후 *최소 1회*. |
| `k6-load-runbook` | k6 부하 실행 *절차 가드* — Grafana/Prometheus 환경 + 사용자 명시 신호 대기 + red flag 패턴 + 박제 형식. |

각 스킬의 호출 시점 / 절차 / 산출물은 해당 스킬 SKILL.md 참조.

## 도메인 / 아키텍처

세부 규칙은 본 파일에 중복 기재하지 않는다.

- **표준 Aggregate 패턴 (Model / Service / Facade / V1Dto / JpaRepository + RepositoryImpl)** — `documents/feature/user-architecture/pr.md`.
- **Entity / VO / Domain Service 구분, 레이어드 + DIP** — `docs/presentation/week3.md`.
- **검수 규칙** — `.claude/skills/verify-architecture/SKILL.md`.

## Plan 구조 (주차)

주차 plan 은 두 짝 + decision log 의 세 문서로 구성한다 — 정형 예시 `docs/plan/week5-b.md` + `docs/plan/week5.md` + `docs/plan/week5/decision.md`. 작성 절차 / 매핑 규약 / SSOT 분담은 `create-plan` / `record-decision` SKILL.md 참조.

## 주차 plan PR 진행 정책 (Phase-by-Phase 확인)

사용자가 `"week{n} 진행"`, `"PR{n} 진행"`, `"D-{n} 진행"` 등 *주차 plan 의 PR 단위 작업* 을 요청하면, Claude 는 **Phase 단위로 사용자 확인을 받으며 진행** 한다. 다단계 작업을 *자동으로 묶어서* 한 번에 실행하지 않는다.

**적용 범위**: `docs/plan/week{n}.md` 의 PR / Phase / D-N 으로 구획된 단위 작업. PR3 / PR4 처럼 *Phase A (구현) → Phase M (측정 비교군) → Phase L (k6 부하)* 와 같이 복수 Phase 로 나뉜 작업이 대표.

**절차** — Claude 는 매 Phase 시작 전에 다음을 사용자에게 확인한다:

1. **현재 위치 박제** — 어떤 PR / Phase / 하위 단계 (A-1, A-2, …) 에 있는지 한 줄.
2. **선택지 제시** — `AskUserQuestion` 으로 *다음 진행 옵션* 과 *트레이드오프* 를 1~4 개 박제 (예: "Phase M 6 조합 비교 측정 / Phase L k6 부하 / 다른 PR 로 전환").
3. **사용자 명시 응답 대기** — 사용자가 옵션을 선택하거나 `"해줘"` / `"진행해줘"` 로 확정한 *후에만* 다음 Phase 실행.
4. **Phase 내 sub-단계도 분리** — A-1 → A-2 → A-3 ... 처럼 sub-단계가 있으면 각 sub-단계 종료 시 다음 sub-단계 진입 전 동일 절차 반복.
5. **commit 도 Phase 단위** — Phase 가 끝나면 commit 후 다음 Phase 진입 *전* 사용자 확인.

**금지**:
- 사용자가 `"PR4 진행해줘"` 라고만 했다고 *Phase A → M → L 전체 자동 실행 금지*. Phase A 만 시작하고 Phase M 진입 전 확인.
- 여러 PR 을 묶어 자동 진행 금지 — PR4 끝나면 PR5 진입 전 확인.
- `"모두 진행해줘"` 명시 요청이 있어도 *위험 / 비가역 단계* (k6 부하 / DB 마이그레이션 적용 / push) 는 별도 확인 유지.

**예외 (자동 진행 허용)**:
- `verify-code` → `verify-architecture` → `verify-tests` 자동 게이트 chain (앞 단계 PASS 시 다음 자동).
- 단일 Phase 내 *불가분* 작업 (예: `searchInfos` projection 코드 변경 + 그 변경에 대한 테스트 추가는 한 묶음).
- 사용자가 명시적으로 `"끝까지 한 번에 가도 돼"` / `"Phase A 부터 L 까지 다 해도 돼"` 라고 *해당 범위를 콕 집어* 허용한 경우.

**참조 사례 (PR4 / D-4 캐싱)**: Phase A 는 A-1 (CacheStore SPI) → A-2 (PropertyFacade detail/search) → A-3 (afterCommit evict) → A-4 (Availability cache) → A-5 (reserve/cancel evict) → A-6 (테스트) 의 6 단계로 나뉘었고, 매 단계 종료 시 commit + 다음 단계 진입 전 사용자 확인을 받았다. Phase M / Phase L 은 다음 세션 사용자 신호 대기 중. 본 패턴이 표준.

## 실험 테스트

트레이드오프 결정 (락 / timeout / JPA 옵션 / 격리 수준) 은 *비즈니스 직관* 만으로 박제하지 않는다. Testcontainers 통합 또는 k6 부하로 가설을 반증 가능하게 측정한 뒤 결정한다. 워크플로 (가설 → 설계 → 실행 → 박제 → 재귀 검토 → 코드 삭제) / Skip 조건 / 박제 위치는 `experiment-recurse` SKILL.md 참조.

측정 코드는 `**/src/test/kotlin/**/experiment/` 또는 `k6/local/` (`.gitignore`) — 박제된 결과가 SSOT, 코드는 일회성. **k6 부하 실행 정책**: Grafana/Prometheus 모니터링 환경 구축 + 사용자 명시 신호 (`"준비됐다"` / `"시작해"`) 후에만 발사. Claude 가 임의로 k6 실행 금지. 상세 runbook 은 `k6-load-runbook` SKILL.md 참조.

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우 (= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.

### Md 박제 파일은 commit 금지 (사용자 로컬 검토용)

Claude 는 *코드 / 테스트 / 마이그레이션 SQL* 만 자동으로 stage / commit 한다. 박제용 md 파일은 사용자가 직접 검토 후 본인 시점에 commit 할 로컬 산출물이라 Claude 가 `git add` / `git commit` 대상으로 삼지 않는다 (`-f` 강제 추가도 금지 — 사용자 명시 요청 시만).

대상: `docs/plan/week{n}-b.md`, `docs/plan/week{n}/decision.md`, `documents/feature/{topic}/comparison.md` / `k6-results.md` / `seed-load.md` / `experiments-results.md` 등.

예외: 사용자가 *명시적으로* "decision.md 커밋해줘" 등으로 요청한 경우만.
