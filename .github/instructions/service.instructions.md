---
applyTo: "**/application/**/*.kt,**/*Facade*.kt"
---

# Application Facade 리뷰 기준

`application/{aggregate}/{Aggregate}Facade.kt` 는 트랜잭션 경계 + 입력(Command)·출력(Info) 매핑 + 도메인 서비스/모델 위임만 담당한다. 도메인 규칙을 직접 코딩하면 도메인 서비스(`domain/{aggregate}/{Aggregate}Service`) 또는 도메인 모델(`{Aggregate}Model`) 로 옮기도록 지적한다.

## 트랜잭션 경계

- `@Transactional` 의 위치·전파·`readOnly`·롤백 조건을 점검한다. 조회 전용 유스케이스에 `readOnly = true` 가 빠져 있으면 지적한다.
- `@Transactional` 은 **Facade 한 곳**에만 둔다. 도메인 서비스 / 모델에 중첩 선언이 있으면 트랜잭션 경계 의도가 흐려지므로 지적한다.
- 트랜잭션 안에서 외부 HTTP 호출이 일어나면 강하게 지적한다(트랜잭션 길이 / 타임아웃 / 재시도 측면).

## 외부 의존

- 외부 의존성(HTTP·DB·메시지) 호출에는 타임아웃·재시도·서킷브레이커 / 멱등성·중복 처리 방지 전략이 있는지 점검한다.
- 결제·예약처럼 부수효과가 외부에서 관측되는 호출은 재시도/중복 실행 시나리오를 명시하도록 권한다.

## 책임 분리

- 도메인 규칙은 도메인 객체(`UserModel.changePassword` 등) 또는 도메인 서비스(`UserService.signUp`)에 두고 Facade 는 흐름만 조립한다. Facade 가 정책 분기/검증을 직접 들고 있으면 지적한다.
- 같은 비즈니스 동작에서 `authenticate` 같은 비싼 검증(BCrypt 매칭 등) 이 중복 호출되지 않는지 본다 — 도메인 서비스와 Facade 헬퍼가 같은 검증을 두 번 수행하면 BCrypt 비용이 두 배가 된다.
- "사용자 없음" 과 "비밀번호 불일치" 를 구분하는 응답을 내보내면 계정 enumeration 위험으로 지적한다 — 둘 다 동일한 `UNAUTHORIZED` 와 동일한 메시지로 응답해야 한다.

## 입력·출력 모델

- 입력은 `application/{aggregate}/command/` 의 `*Command` 로 받는다. Facade 가 `interfaces.api.**.*Dto`/`*Request` 를 직접 import 하면 지적한다 — DTO 변환은 컨트롤러 경계에서 끝나야 한다.
- 출력은 `{Aggregate}Info` 로 반환한다. 도메인 모델(`{Aggregate}Model`) 을 그대로 외부에 노출하지 않는다.
- `{aggregate}Repository.save(...)` 호출이 dirty checking 으로 충분한 상황에서 들어 있으면, 의도(테스트 fake 호환 등) 를 확인 질문으로 남긴다.
