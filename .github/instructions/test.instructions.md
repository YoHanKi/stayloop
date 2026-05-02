---
applyTo: "**/test/**/*.kt,**/*Test*.kt"
---

# 테스트 리뷰 기준

## 피라미드 커버리지

- domain 변경: 단위 테스트(`{Aggregate}ModelTest`, `{Aggregate}ServiceTest`) 가 따라가는지 본다. 값 객체·불변식·상태 전이 검증이 빠져 있으면 지적한다.
- application 변경: `{Aggregate}FacadeTest` (도메인 서비스를 mock 하지 않고 실제 객체로 묶어 InMemory + Fake 로 흐름 검증) 가 있는지 본다.
- interfaces.api 변경: `{Aggregate}V1ControllerTest` (`@WebMvcTest` + `MockkBean facade`) 의 인증 헤더 누락/검증 실패/CONFLICT/UNAUTHORIZED 경로 회귀 가드를 본다.
- 빠진 레이어가 있다면 정당화를 요구한다("도메인 규칙 없는 단순 위임" 같은 사유 없이 빠진 것은 누락).

## 시나리오 카테고리

- 단위 테스트는 정상 흐름 / 경계값 / 실패 케이스 / 예외 흐름을 모두 다루는지 본다. happy path 만 있는 테스트는 지적한다.
- 보안 회귀 가드(예: "사용자가 없을 때도 UNAUTHORIZED 로 응답" / "Password.toString 이 평문을 노출하지 않음") 가 테스트 이름으로 박혀 있는지 본다. 없으면 회귀 위험을 짚는다.
- 테스트 이름에 "(존재 노출 금지)", "(계정 enumeration 차단)" 같은 의도 흔적은 의도된 정책이므로 보존하도록 권한다.

## 명세성

- `@DisplayName` 한국어로 의도가 드러나는지 본다(예: `"signUp() 은 회원을 저장하고 마스킹된 정보를 반환한다."`). 테스트 이름이 메서드명만 따라가는 경우는 지적한다.
- 테스트 본문은 `// arrange / act / assert` 또는 `given/when/then` 으로 구분되어 있는지 본다.
- 함수명은 `shouldXxx_whenYyy` 형태로 결과 + 조건을 표현한다.

## 테스트 더블

- 도메인 객체(엔티티, 값 객체) 를 mock 하지 않는다. 도메인 단위 테스트가 정책을 검증해야 하고, fake 가 정책을 대신 들고 있으면 책임 분리가 깨진 것이다.
- `FakePasswordEncoder` 같은 흐름용 fake 가 정책 검증을 추가로 들고 있으면 의미를 흐리는 변경으로 지적한다.
- 한 테스트에서 mock 이 4개 이상이면 유스케이스 분해 부족 신호로 본다.
- 단순 응답이 필요한 자리에 `verify(...)` 가 남용되어 있으면 stub 으로 충분하다고 지적한다. 부수효과 호출 검증이 빠져 있으면 mock 누락으로 지적한다.

## 통합 테스트

- DB / 외부 의존성 격리, 테스트 데이터 준비·정리, 플래키 가능성을 점검한다.
- 테스트 fake 안의 reflection (예: `BaseEntity::class.java.getDeclaredField("id")`) 은 프로덕션이 아닌 fake 에 격리돼 있는 한 허용한다. 필드명이 바뀌면 런타임에서만 깨지므로 PR 변경에서 함께 손이 가는 자리인지 확인한다.
