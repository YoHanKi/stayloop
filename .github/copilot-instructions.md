# Stayloop — Copilot 리뷰 공통 지침

Spring Boot 3 + Kotlin 멀티모듈 숙박 예약 백엔드. 모듈: `apps/`(실행) / `modules/`(재사용 config) / `supports/`(애드온).

> Copilot code review 는 인스트럭션 파일의 첫 4000자만 읽고, PR 본문(예: "## 리뷰 포인트")을 항상 읽는다고 보장하지 않는다. 모든 규칙은 본문 없이도 단독으로 동작하는 task-agnostic 형태로 적는다.

## 톤

- 한국어, 표준어, 공식체. 문장 끝은 '다'.
- 칭찬·장식 금지. 운영/장애/보안/성능/테스트 관점만.
- 지적 3요소 필수: (1) 왜 문제인지(운영·보안·성능 결과) (2) 수정 제안(가능하면 코드) (3) 회귀 방지 테스트 시나리오.
- 추측은 단정 금지 — "확인 부탁드린다" 형태로 남긴다.

## 리뷰 우선순위

1. **보안** — 평문 비밀번호·토큰 누수, 계정 enumeration, 로그 민감정보, 인증/인가 우회.
2. **데이터 정합성** — 트랜잭션 경계, race window, 멱등성, JPA dirty checking 의도.
3. **운영성** — 타임아웃, 재시도, 외부 의존 실패 처리, 로깅 레벨/맥락.
4. **도메인 표현** — VO `init` 검증 책임, 엔티티 캡슐화, 도메인 규칙 응집.
5. **가독성·관용** — scope function 남용, `!!` 사용, 불필요한 추상화·어댑터.

## 아키텍처 경계 (위반 시 강하게 지적)

- 의존 방향: `domain ← application ← interfaces.api / infrastructure`. 역참조 금지.
- Aggregate 패키지:
  - `domain/{aggregate}/`: `{Aggregate}Model` (엔티티) / `{Aggregate}Repository` (도메인 인터페이스) / `{Aggregate}Service` (도메인 서비스, 선택) / `value/`.
  - `application/{aggregate}/`: `{Aggregate}Facade` / `{Aggregate}Info` / `command/`.
  - `interfaces/api/{aggregate}/`: `{Aggregate}V1Controller` + `{Aggregate}V1ApiSpec` + `{Aggregate}V1Dto`.
  - `infrastructure/{aggregate}/`: `{Aggregate}JpaRepository` (`JpaRepository<...>`만 상속) + `{Aggregate}RepositoryImpl` (`@Component`, 도메인 Repository 구현 + JpaRepository 위임).
- 어노테이션 위치: `@Entity`/`@Embeddable` = domain only, `@Transactional` = application Facade only, `@RestController` = interfaces.api only, `JpaRepository` = infrastructure only.

## 수용된 트레이드오프 (재지적 금지)

이 프로젝트에서 의식적으로 받아들인 결정. 같은 지적을 반복하지 않는다. 단, 이로 인한 *추가* 리스크는 짚는다.

- 도메인 패키지에 `jakarta.persistence.*` 침투 — JPA 친화 모델링 우선. 단 `org.springframework.*` 의존은 여전히 금지.
- 테스트 fake 의 reflection (`BaseEntity::class.java.getDeclaredField("id")`) — JPA id 자동 할당 흉내, fake 한 곳 격리.
- `var ... protected set` 본문 선언 — 외부 read-only / 내부 mutable 캡슐화.
- "사용자 없음" / "비밀번호 불일치" 동일 `UNAUTHORIZED` + 동일 메시지 — 계정 enumeration 차단.
- 가입 race window 는 `ApiControllerAdvice` 가 `DataIntegrityViolationException` → `CONFLICT` 매핑.

## 자명한 컨벤션

- 예외는 `CoreException(ErrorType, message)` 으로 통일하고 `ApiControllerAdvice` 가 매핑한다.
- 입력 검증은 도메인 VO `init` 에 둔다. Bean Validation 어노테이션이 도메인 VO 에 새면 지적.
- 비밀번호·시크릿은 `toString` / 로그 / 직렬화 어디에서도 노출 금지.
- 테스트 더블은 흐름만 모사. 도메인 정책 검증을 fake 가 들고 있으면 책임 분리 위반.

## 리뷰 제외

- 문서(`**/*.md`, `**/*.adoc`), 이미지, 락 파일, 자동 생성물(`generated/`, `build/`, `.gradle/`).
- 단순 포매팅·rename·ktlint 자동 수정.

## 응답 형식

- PR 요약: 변경 목적 / 핵심 변경 / 리스크 / 검증 방법 — 4~8줄.
- 라인 코멘트: 한 코멘트 = 한 주제. 모호하면 "확인 부탁드린다" 로 닫는다.
