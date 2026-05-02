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
