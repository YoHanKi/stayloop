---
applyTo: "**/*Controller*.kt,**/*Controller*.java,**/interfaces/api/**/*.kt"
---

# Controller / API 표면 리뷰 기준

- Controller 는 요청 검증과 응답 조립에만 집중한다. 비즈니스 분기·트랜잭션 흐름은 Service 로 이동시키도록 권한다.
- 엔티티(`@Entity`) 를 직접 응답으로 반환하지 않는지 점검한다. 응답 DTO(`*Response`) 와 도메인 객체는 분리한다.
- 상태 코드와 에러 응답 포맷이 `ApiResponse` / `ApiControllerAdvice` 의 표준 흐름을 따르는지 확인한다. Controller 안에 `try/catch` 로 매핑하는 코드는 지적한다.
- 헤더 인증은 `LoginCredentialsArgumentResolver` 를 통해 `LoginCredentials` 파라미터로 받는다. `@RequestHeader("X-Loopers-LoginId")` 가 컨트롤러에 직접 등장하면 일관성 위반으로 지적한다.
- 인증 헤더 누락 = `UNAUTHORIZED`, 헤더 형식 위반 = `BAD_REQUEST` 분기를 점검한다. 이 둘이 섞이면 호출자 디버깅이 어려워진다.
- Bean Validation 어노테이션은 요청 DTO 에 둔다. 도메인 VO 에 `@NotBlank` 같은 제약이 새지 않는지 본다.
- 응답에 평문 비밀번호·해시·내부 토큰이 노출되지 않는지 확인한다. `UserResponse` 처럼 마스킹된 값을 쓰는 패턴이 무너졌는지 점검한다.
