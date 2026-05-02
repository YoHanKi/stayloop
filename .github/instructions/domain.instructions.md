---
applyTo: "**/domain/**/*.kt,**/domain/**/*.java"
---

# 도메인 모델 리뷰 기준

- 값 객체 / 엔티티 경계가 분명한지 본다. 식별자가 없는 모델이 엔티티로 선언돼 있으면 지적한다.
- 입력 형식 검증은 VO 의 `init` 에 둔다. Service / Controller 가 같은 검증을 다시 수행하고 있으면 책임 중복으로 지적한다.
- 비밀번호 같은 민감 VO 는 다음을 모두 충족하는지 본다.
  - `private constructor` + 단일 정적 팩토리 진입점(`ofRaw` 등). 우회 팩토리(`ofEncoded`, `unsafe(...)` 류) 는 강하게 지적한다.
  - `toString` 이 평문/해시를 노출하지 않음.
  - `equals` / `hashCode` 가 평문 비교에 의존하지 않음.
- 엔티티의 진입점은 `companion.create(...)` 로 통일하고 외부 주 생성자는 `internal` / `protected` 로 닫는다. `public` 으로 열려 있으면 보안 게이트(예: `Password.ofRaw`) 우회 가능성을 지적한다.
- 가변 필드는 `var` + `protected set` 패턴을 권장한다. `var` 가 외부에서 직접 할당 가능하게 열려 있으면 캡슐화 위반으로 지적한다.
- 비밀번호 정책처럼 다른 VO 와 교차하는 검증은 시그니처에 명시한다(`Password.ofRaw(raw, birthDate, encoder)`). 호출자 입장에서 시그니처가 곧 문서가 된다.
- 도메인 객체에 `org.springframework.*` 의존이 새면 지적한다. `jakarta.persistence.*` 침투는 본 프로젝트에서는 의식적으로 받아들인 트레이드오프이므로 추가 지적하지 않는다.
- 마스킹·표현 변환(`masked()`, `compact()`, `isoString()` 등) 은 VO 의 책임이다. 응답 DTO 가 마스킹 로직을 직접 들고 있으면 책임 위치를 지적한다.
