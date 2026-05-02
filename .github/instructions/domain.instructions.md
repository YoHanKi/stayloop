---
applyTo: "**/domain/**/*.kt"
---

# 도메인 레이어 리뷰 기준

`com.stayloop.domain.{aggregate}` 하위는 `{Aggregate}Model`(엔티티) / `{Aggregate}Repository`(인터페이스) / `{Aggregate}Service`(도메인 서비스, 선택) / `value/`(값 객체) 로 구성한다.

## 엔티티 (`{Aggregate}Model`)

- 값 객체 / 엔티티 경계가 분명한지 본다. 식별자가 없는 모델이 엔티티로 선언돼 있으면 지적한다.
- 엔티티 이름은 `{Aggregate}Model` 접미사를 따른다(예: `UserModel`). 단순 `User` 같은 명명은 호출 측 컨텍스트에서 모호하므로 지적한다.
- 진입점은 `companion.create(...)` 로 통일하고 외부 주 생성자는 `internal` / `protected` 로 닫는다. `public` 으로 열려 있으면 보안 게이트(예: `Password.ofRaw`) 우회 가능성을 지적한다.
- 가변 필드는 `var` + `protected set` 패턴을 권장한다. `var` 가 외부에서 직접 할당 가능하게 열려 있으면 캡슐화 위반으로 지적한다.

## 도메인 서비스 (`{Aggregate}Service`, 선택)

- 엔티티 한 인스턴스로 표현되지 않는 협력(중복 검사·인증·집합 연산) 만 도메인 서비스에 둔다. 단순 위임이면 Facade 에 흡수하도록 권한다.
- 도메인 서비스는 `application` / `infrastructure` / `interfaces` 의 어떤 심볼도 import 하지 않는다 — 입력은 값 객체로 받는다(`SignUpCommand` 같은 application 타입 직접 의존 금지).
- `@Transactional` 은 도메인 서비스에 두지 않는다. 트랜잭션 경계는 Facade 책임.

## 값 객체 (`value/`)

- 입력 형식 검증은 VO `init` 에 둔다. Service / Controller 가 같은 검증을 다시 수행하면 책임 중복으로 지적한다.
- 마스킹·표현 변환(`masked()`, `compact()`, `isoString()` 등) 은 VO 의 책임이다. 응답 DTO 가 마스킹 로직을 직접 들고 있으면 책임 위치를 지적한다.
- 비밀번호 정책처럼 다른 VO 와 교차하는 검증은 시그니처에 명시한다(`Password.ofRaw(raw, birthDate, encoder)`).

## 민감 VO (Password 등)

- `private constructor` + 단일 정적 팩토리 진입점(`ofRaw` 등). 우회 팩토리(`ofEncoded`, `unsafe(...)` 류) 는 강하게 지적한다.
- `toString` 이 평문/해시를 노출하지 않는다.
- `equals` / `hashCode` 가 평문 비교에 의존하지 않는다.

## 의존 방향

- 도메인 객체에 `org.springframework.*` 의존이 새면 지적한다(`@Service` 어노테이션 등 Spring stereotype 1개 수준은 도메인 서비스에서 허용).
- `jakarta.persistence.*` 침투는 본 프로젝트에서는 의식적으로 받아들인 트레이드오프이므로 추가 지적하지 않는다.
- 도메인 Repository 인터페이스에 `JpaRepository`/`@Query`/`Pageable` 같은 인프라 타입이 새지 않는지 본다.
