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

## 도메인 & 객체 설계 전략

도메인 객체는 **비즈니스 규칙을 캡슐화**하는 곳이다. Application 은 도메인 객체를 조립해 유스케이스를 완성한다.

- **규칙의 위치**
  - 한 인스턴스 안에서 닫히는 규칙(예: `RoomType.checkGuestCount(n)`, `DailyRoomInventory.reserveOne()`) 은 **모델 메서드**.
  - 같은 Aggregate 안에서 여러 객체가 협력해야 하는 규칙(예: 일자별 요금 합산, 회원 가입 시 LoginId 중복 검사) 은 **도메인 서비스**(`*Service`).
  - 여러 도메인을 가로질러 조립해야 하는 흐름(예: 숙소 상세 = Property + RoomType + Inventory + Rate) 은 **애플리케이션 Facade**.
  - 같은 규칙이 여러 Facade 에 등장하기 시작하면 도메인 쪽으로 끌어올려야 한다는 신호.
- **Aggregate 명명**
  - 엔티티는 `*Model` 접미사 (예: `UserModel`, `PropertyModel`). "User" 같은 단어는 보안/세션/비즈니스 컨텍스트가 모호하므로 `Model` 로 의도를 명시.
  - 값 객체는 `domain/<aggregate>/value/` 하위. 불변, 같은 값 == 같은 객체 (`LoginId`, `Money`, `StayPeriod` 등).
  - Repository 인터페이스는 `domain/<aggregate>/<Aggregate>Repository.kt`. 도메인이 정의하고 인프라가 구현한다.
  - Aggregate 내부에 도메인 서비스가 필요하면 `domain/<aggregate>/<Aggregate>Service.kt`.
- **시간/일자 기반 데이터**
  - 일자별 재고·요금은 `(room_type_id, date)` 단위 도메인 객체로 모델링한다. 단일 컬럼 잔여 수량 같은 모델은 금지(5월 10일과 11일을 구분 불가).
- **도메인 서비스의 의존 방향 — 절대 규칙**
  - 도메인 서비스는 `application` 패키지를 import 하지 않는다. Command/Info 같은 application 입력·출력 모델을 도메인 시그니처에 끌어들이면 의존 방향이 뒤집힌다.
  - Facade 가 Command → 값 객체 변환을 담당하고, 도메인 서비스는 값 객체를 개별 인자로 받는다 (`UserService.signUp(loginId, name, ...)` 패턴).
- **불필요한 추상화 금지**
  - 사용처가 1곳인 helper 클래스, 미구현 도메인을 위한 nullable 인자(예: 쿠폰 미구현 시 `Coupon?` 인자)는 만들지 않는다.

## 아키텍처 / 패키지 구성

레이어드 아키텍처 + DIP. 의존은 **항상 안쪽으로만** 흐른다.

```
interfaces.api  →  application  →  domain  ←  infrastructure
```

### 패키지 규약 (`apps/stay-api/src/main/kotlin/com/stayloop/`)

| 레이어 | 패키지 | 들어가는 것 |
|---|---|---|
| Interfaces | `interfaces/api/<aggregate>/` | `*V1Controller`(라우팅·위임만), `*V1ApiSpec`(OpenAPI 명세 인터페이스), `*V1Dto`(요청/응답 DTO 를 중첩 `data class` 로 모은 컨테이너) |
| Application | `application/<aggregate>/` | `*Facade`(트랜잭션 경계 + DTO 매핑 + 도메인 조립), `*Info`(애플리케이션 출력), `command/*Command`(Facade 입력) |
| Domain | `domain/<aggregate>/` | `*Model`(엔티티), `value/*`(VO), `*Repository`(인터페이스), `*Service`(도메인 서비스), 외부 정책 인터페이스(예: `PasswordEncoder`) |
| Infrastructure | `infrastructure/<aggregate>/` | `*JpaRepository`(순수 `JpaRepository` 상속), `*RepositoryImpl`(@Component, 도메인 인터페이스 구현 + JPA 위임), 외부 정책 어댑터(예: `BCryptPasswordEncoderAdapter`) |

### 검수 포인트

- **DTO 분리**: API request/response DTO ≠ Application DTO. Controller 는 `*V1Dto` 를 Command 로 변환해 Facade 에 넘기고, Facade 는 `*Info` 를 반환한다. 도메인 모델이 컨트롤러 응답으로 새지 않게 한다.
- **JPA 어노테이션 누출 금지**: `@Entity`/`@Table`/`@Id` 등은 `*Model` 에 붙되, **Spring Data 의 `JpaRepository` 가 도메인 `*Repository` 인터페이스에 섞이지 않게** 한다 (다중 상속 금지). `*JpaRepository : JpaRepository<*Model, ID>` 와 `*RepositoryImpl : *Repository` 는 분리한다.
- **트랜잭션 경계**: `@Transactional` 은 Application Facade 에만 둔다. 도메인 서비스에 중첩 선언 금지. 읽기 전용은 `@Transactional(readOnly = true)`.
- **Controller 라우팅 외 책임 금지**: 비즈니스 규칙·검증·매핑은 Facade/도메인으로 위임. Controller 본문은 위임 한 줄이 이상적.
- **테스트 가능성**: `domain/<aggregate>/*Repository` 의 인메모리 더블(`support/test/InMemory*Repository`) 을 둬서 도메인/애플리케이션 단위 테스트는 Spring 컨텍스트 없이 실행 가능해야 한다. `*FacadeTest` 는 도메인 객체를 mock 하지 않고 실제 객체로 묶어 통합성을 확보한다(컨트롤러 E2E 에서만 `*Facade` 를 mock).

## 커밋

- 메시지 형식: `<prefix> : <한 줄 요약 (한국어, 마침표).>` (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`)
- **`Co-Authored-By: Claude ...` 트레일러는 기본적으로 추가하지 않는다.** 일반 기능·버그·리팩토링·마이그레이션 커밋의 작성자는 사람이며, Claude 가 공동 작성자로 표기되면 git blame / 기여도 추적이 혼동된다.
- 예외: 변경이 **CLAUDE.md** 또는 `.claude/skills/**` 에 한정된 경우(= Claude 와의 협업 자체가 변경의 본질) 에 한해 `Co-Authored-By` 트레일러를 허용한다.
