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

| 스킬 | 용도 | 호출 시점 |
|---|---|---|
| `verify-tests` | 단위/통합/E2E 테스트 충분성·구조·더블·실패 시나리오 점검 + `./gradlew ktlintCheck && test` 강제 실행 | **기능 구현/변경이 끝나는 즉시, 사용자에게 작업 완료를 보고하기 전에 반드시 호출.** 결과가 `FAIL` 인 동안 작업은 미완료. |

예외: 사용자가 명시적으로 스킵을 지시했거나, 변경이 순수 문서·메타 파일에 한정된 경우.
