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

## 도메인 / 아키텍처

세부 규칙은 본 파일에 중복 기재하지 않는다. 새 Aggregate 를 만들거나 기존 구조를 검수할 때 다음을 참조한다.

- **표준 Aggregate 패턴 (Model / Service / Facade / V1Dto / JpaRepository + RepositoryImpl)** — `documents/feature/user-architecture/pr.md`. 회원 도메인이 정렬한 구조를 그대로 답습한다.
- **Entity / VO / Domain Service 구분, 레이어드 + DIP 원칙** — `docs/presentation/week3.md`.
- **검수 규칙 (의존 방향, 명명, 트랜잭션 경계, JPA 어노테이션 누출 등)** — `.claude/skills/verify-architecture/SKILL.md`. 위반 여부는 본 스킬을 호출해 판정한다.

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우(= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.
