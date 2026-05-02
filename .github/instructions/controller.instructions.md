---
applyTo: "**/interfaces/api/**/*.kt"
---

# Controller / API 표면 리뷰 기준

`interfaces/api/{aggregate}/` 는 `{Aggregate}V1Controller` + `{Aggregate}V1ApiSpec`(OpenAPI 명세 인터페이스) + `{Aggregate}V1Dto`(요청·응답 중첩 `data class`) 로 구성한다.

## 책임 분리

- Controller 는 요청 바인딩 → Command 변환 → Facade 호출 → DTO 매핑에만 집중한다. 비즈니스 분기·트랜잭션 흐름은 Facade / 도메인 서비스로 이동시키도록 권한다.
- Controller 가 `Repository` / `JpaRepository` 를 직접 주입받으면 강하게 지적한다 — 호출은 반드시 `{Aggregate}Facade` 를 거친다.
- `{Aggregate}V1Controller` 가 `{Aggregate}V1ApiSpec` 인터페이스를 구현하지 않으면 OpenAPI 명세와 구현이 분리되지 않은 것으로 지적한다.

## DTO 경계

- 엔티티(`@Entity` / `*Model`) 를 직접 응답으로 반환하지 않는지 점검한다. 반드시 `{Aggregate}Info` → `{Aggregate}V1Dto.*Response` 매핑을 거친다.
- 요청·응답 DTO 는 `{Aggregate}V1Dto` 의 중첩 `data class` 로 모은다. `dto/` 하위 디렉토리에 흩어지면 패턴 이탈로 지적한다.
- Bean Validation 어노테이션은 요청 DTO 에 둔다. 도메인 VO 에 `@NotBlank` 같은 제약이 새지 않는지 본다.

## 인증·예외

- 헤더 인증은 `LoginCredentialsArgumentResolver` 를 통해 `LoginCredentials` 파라미터로 받는다. `@RequestHeader("X-Loopers-LoginId")` 가 컨트롤러에 직접 등장하면 일관성 위반으로 지적한다.
- 인증 헤더 누락 = `UNAUTHORIZED`, 헤더 형식 위반 = `BAD_REQUEST` 분기를 점검한다. 이 둘이 섞이면 호출자 디버깅이 어려워진다.
- 상태 코드와 에러 응답 포맷이 `ApiResponse` / `ApiControllerAdvice` 의 표준 흐름을 따르는지 확인한다. Controller 안에 `try/catch` 로 매핑하는 코드는 지적한다.

## 보안 응답

- 응답에 평문 비밀번호·해시·내부 토큰이 노출되지 않는지 확인한다. `*Response` 가 마스킹된 VO(`Name.masked()`, `PhoneNumber.masked()`) 를 사용하는 패턴이 무너졌는지 점검한다.
- "사용자 없음" / "비밀번호 불일치" 응답 분기가 컨트롤러로 새 들어오면 계정 enumeration 위험으로 지적한다.
