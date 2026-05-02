---
applyTo: "**/*.kt"
---

# Kotlin 공통 리뷰 기준

- null-safety 를 최우선으로 본다. `!!` 는 불가피한 경우에만 허용하고 근거를 요구한다. 대안은 `requireNotNull` / `?: error(...)` / 도메인 예외(`CoreException`).
- `lateinit var` 가 외부에서 set 되는 구조면 캡슐화 위반으로 본다. 필요한 경우 `private set` 또는 생성자 주입으로 유도한다.
- scope function(`let` / `apply` / `run` / `also` / `with`) 의 남용으로 가독성이 떨어지면 명시적인 코드로 대안을 제시한다.
- 컬렉션 연산은 중간 리스트 생성을 점검한다. 대량 처리에서는 `asSequence()` 또는 단순 반복문을 권한다.
- `data class` 는 값 의미를 가질 때만 사용한다. JPA 엔티티에 대한 `data class` 사용은 원칙적으로 지양하고 `equals` / `hashCode` 안정성(식별자 기반)을 점검한다.
- 예외는 도메인 예외(`CoreException(ErrorType, ...)`) 와 인프라 예외를 구분한다. 인프라 예외를 그대로 컨트롤러까지 던지지 않는다.
- 로깅에서 비밀번호·토큰·헤더 값이 그대로 찍히지 않는지 점검한다. `e.message` 만으로 끝내지 말고 맥락(요청 식별자·도메인 키)을 함께 남길 수 있는지 확인한다.
- `companion object` 의 정적 상수는 `const val` 로 둘 수 있는 경우(`String` / 기본형) 그렇게 권한다.
- Kotlin reflection (`::class.java.getDeclaredField` 등) 는 프로덕션 코드에서 발견되면 강하게 지적한다. 테스트 fake 안에 격리된 경우에는 허용한다.
