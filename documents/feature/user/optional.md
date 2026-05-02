# PR-01 — Round 1 회원 도메인 구현: 요구사항을 코드로 풀어내며 만난 고민들

> `docs/presentation/week1-quests.md` 의 회원 도메인 요구사항(회원가입 · 내 정보 조회 · 비밀번호 수정)을 stayloop 의 첫 도메인으로 구현하면서, 매 단계에서 어떤 선택지가 있었고 무엇을 골랐으며 어떤 비용을 감수했는지 정리한다.
> 작업 결과: `apps/stay-api` 의 `domain.user`, `application.user`, `interfaces.api.user`, `interfaces.api.auth` 패키지 — 6개 값 객체와 `User` 엔티티, 3개 유스케이스, 헤더 기반 인증, 마스킹 응답.

---

## 0. 출발점 — Round 1 요구사항이 부르는 결정들

week1-quests 의 회원 요구사항은 짧지만 결정해야 할 가지가 많다.

| 요구사항 | 코드가 풀어야 하는 질문 |
|---|---|
| **회원가입** — 6개 정보(로그인 ID, 비밀번호, 이름, 생년월일, 이메일, 휴대폰)를 형식 검증 후 저장. 로그인 ID 중복 금지. 비밀번호는 암호화 저장 + 정책(8~16자, 영문·숫자·특수문자, 생년월일 포함 금지) | 입력 검증 책임을 어디에 둘까? 평문 비밀번호가 객체 외부에 새지 않으려면? "비밀번호에 생년월일 금지" 같은 교차 검증은 어느 객체가 갖나? |
| **내 정보 조회** — 매 요청마다 `X-Loopers-LoginId` / `X-Loopers-LoginPw` 헤더로 인증. 이름은 끝글자, 휴대폰은 가운데 자리 마스킹 | 헤더 두 개로 매번 인증하는 패턴을 컨트롤러마다 반복하지 않으려면? 마스킹은 누구의 책임? 존재하지 않는 ID 와 비밀번호 틀림은 어떻게 구분(혹은 안 구분)? |
| **비밀번호 수정** — 현재 비밀번호 검증 후 새 비밀번호로 교체. 새 비밀번호도 동일 정책 + 현재와 같으면 거절 | 변경 로직이 도메인이냐 서비스냐? `password` 필드는 `val` 인가 `var` 인가? |

이 질문들을 풀어가면서 부수적으로 영속 모델(어떻게 JPA 와 묶을지), 클래스 가시성, `kotlin-allopen` 같은 인프라 결정도 따라왔다. 이 문서는 그 의사결정의 흐름을 시간순(엇비슷)으로 따라간다.

---

## 1. 고민 — 입력 포맷 검증을 어디에 둘 것인가

### 문제

요구사항은 6개 입력 모두에 형식 검증이 필요하다고 못박는다 — 이메일 형식, 휴대폰 `010-XXXX-XXXX`, 이름 비공백, 생년월일 형식, 로그인 ID 영문·숫자, 비밀번호 정책. 검증을 한 자리에 모으지 않으면 컨트롤러·서비스·도메인 모두에 흩어져 같은 정규식이 두세 번 등장한다.

### 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| (A) 컨트롤러 `@Valid` + Bean Validation | 표준, 어노테이션으로 간결 | 도메인이 영속/처리 단계에서 무방비 — DTO 만 통과하면 끝. VO 가 검증된 사실을 타입이 보장 못 함 |
| (B) Service 진입에서 검증 | 한 자리 모임 | 어떤 String 이 검증된 것인지 타입으로 구분 불가. 다른 서비스 메서드에서 또 검증 필요 |
| **(C) 도메인 값 객체(VO)의 `init` 블록** | 검증된 인스턴스 = 타입 자체로 안전. 한 번 만들면 어디서 재사용해도 보장됨 | 외부 입력 → VO 생성 시점에 예외, 매핑 코드 1단계 추가 |

### 선택: (C) — 값 객체 `init` 검증

`Email`, `LoginId`, `Name`, `BirthDate`, `PhoneNumber`, `Password` 6종 모두 VO 로 만들고, 형식 검증을 `init` 안에 넣었다.

```kotlin
@Embeddable
data class PhoneNumber(
    @Column(name = "phone_number", nullable = false, length = 20)
    val value: String,
) {
    init {
        if (!REGEX.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "휴대폰 번호는 010-XXXX-XXXX 형식이어야 합니다.")
        }
    }
    companion object { private val REGEX = Regex("^010-\\d{4}-\\d{4}$") }
}
```

이유:
- **타입이 곧 계약**이다. `PhoneNumber` 인스턴스가 손에 있으면 `010-XXXX-XXXX` 형식임이 보장된다 — 다음 레이어가 다시 검증할 동기가 사라진다.
- 컨트롤러는 단순 매핑만 한다 — `SignUpRequest.toCommand()` 가 `PhoneNumber(rawString)` 으로 만들고, 형식 위반은 자연스럽게 `CoreException(BAD_REQUEST)` → `ApiControllerAdvice` 가 처리.
- Bean Validation 어노테이션이 도메인 패키지에 들어오지 않는다.

### 따라온 부수 결정 — `@JvmInline value class` 가 아니라 `@Embeddable data class`

처음엔 이 6개 VO 를 `@JvmInline value class` 로 두는 것이 자연스러워 보였다 — Kotlin 의 idiomatic 한 값 표현이고, 런타임 오버헤드가 없다. 그러나 영속화를 붙이는 순간 막혔다.

- **`@JvmInline value class` 는 JPA 엔티티 필드로 못 쓴다.** Hibernate 가 리플렉션으로 필드 타입을 보고 매핑을 잡는데, value class 는 JVM 레벨에서 인라인되어 사라진다 → Hibernate 가 보지 못한다.
- 대안 (A) "`UserJpaEntity` 가 String 들고, 어댑터에서 변환" 은 → 영속 경계마다 `Email` 정규식이 재실행되는 비대칭이 생긴다.
- 대안 (B) `AttributeConverter` 등록은 → 컨버터 N개 + `Money(amount, currency)` 같은 다중 필드 타입을 만나면 무너진다.
- 선택 (C) `@Embeddable data class` 로 — 도메인 = 영속 타입. `kotlin-jpa` 플러그인이 합성 no-arg 생성자를 만들어주므로 `data class` 인 채로도 동작한다. `init` 은 외부 입력에서만 실행되고 JPA 하이드레이션 시엔 실행되지 않는다 → DB 정합성 신뢰 + 외부 입력 검증의 비대칭이 깔끔하게 풀린다.

받아들인 비용: 도메인 패키지에 `jakarta.persistence.*` 임포트가 들어온다(`@Embeddable`, `@Column`). 헥사고날 순수성을 양보한 것 — 다음 절(고민 8)에서 이 양보를 한 번 더 키운다.

---

## 2. 고민 — 평문 비밀번호가 클래스 외부로 새지 않으려면

### 문제

요구사항: "비밀번호는 암호화해 저장 + 8~16자에 영문 대소문자·숫자·특수문자만 허용 + 생년월일 포함 금지." 한 줄 요구사항 뒤엔 보안 침투 경로가 줄줄이 있다.

- 평문 String 이 도메인 객체 필드로 살아남으면 → `toString()`, 직렬화, 디버깅 로그로 새어나간다.
- `User(loginId, plainPassword, ...)` 같은 생성자가 외부에 노출되면 → 호출자가 인코딩을 깜빡할 수 있다.
- 정책(허용 문자 집합 + 길이 + 생년월일 미포함)을 통과하지 않은 인스턴스가 존재할 수 있다.

> 정책 해석 노트 — week1-quests 원문 *"8~16자의 영문 대소문자, 숫자, 특수문자만 가능합니다"* 는 **허용되는 문자 집합의 화이트리스트**로 읽었다. 즉 `Abcd1234` 처럼 특수문자가 없어도 통과한다 — 셋을 모두 포함해야 한다는 뜻이 아니다. 그래서 정규식도 `^[A-Za-z0-9!@#...]{8,16}$` 한 덩어리이지, "각 카테고리 최소 1개" 를 강제하는 lookahead 가 들어가지 않는다. 정책을 강화하려면(셋 다 필수) 명세 변경 + 정규식 + 테스트를 함께 손대야 한다.

### 검토한 대안

| 대안 | 어디서 인코딩 | 정책 검증 위치 | 평문 노출 가능성 |
|---|---|---|---|
| (A) 서비스에서 `encoder.encode(raw)` 후 `Password(encoded)` | 서비스 | 서비스 | 서비스마다 잊을 수 있음 |
| (B) `Password(raw, encoder)` 생성자가 알아서 인코딩 | 생성자 | 생성자 | 외부에 평문 String 으로 들어왔다 나가는 동선 짧음 |
| **(C) `Password.ofRaw(raw, birthDate, encoder)` 정적 팩토리 + `private constructor`** | 팩토리 내부 | 팩토리 내부 | 외부에서 `Password` 직접 생성 불가, 평문 동선 한 곳 |

### 선택: (C) — 정적 팩토리 + 정책 강제 + private 생성자

```kotlin
@Embeddable
class Password private constructor(
    @Column(name = "password", nullable = false, length = 100)
    val encoded: String,
) {
    fun matches(raw: String, encoder: PasswordEncoder): Boolean = encoder.matches(raw, encoded)

    override fun toString(): String = "Password(encoded=***)"

    companion object {
        private val POLICY_REGEX = Regex("^[A-Za-z0-9!@#\$%^&*()_+\\-=\\[\\]{};:'\",.<>/?\\\\|`~]{8,16}$")

        fun ofRaw(raw: String, birthDate: BirthDate, encoder: PasswordEncoder): Password {
            if (!POLICY_REGEX.matches(raw)) throw CoreException(BAD_REQUEST, "비밀번호는 8~16자...")
            if (raw.contains(birthDate.compact())) throw CoreException(BAD_REQUEST, "비밀번호에 생년월일을 포함할 수 없습니다.")
            return Password(encoder.encode(raw))
        }
    }
}
```

핵심 결정 사항:

- **평문은 절대 `Password` 인스턴스의 필드가 되지 않는다.** `ofRaw` 안에서 `encoder.encode(raw)` 를 먼저 통과시키고, 그 결과만 `encoded` 로 들어간다. 평문 `String` 의 수명은 `ofRaw` 호출 스택 안.
- **`toString()` 은 `***` 로 마스킹.** 엔티티가 로그에 찍혀도 평문 흔적이 안 남는다.
- **`private constructor` + 단일 진입점 `ofRaw`.** 외부에서 `Password(...)` 직접 호출 불가. 정책 검증 + 인코딩을 통과한 인스턴스만 존재할 수 있다. DB 에서 읽어들이는 경로는 JPA 의 리플렉션 하이드레이션이 처리한다(공개 API 통과 안 함).
- **`ofEncoded` 같은 우회 팩토리는 일부러 두지 않는다.** 처음엔 "이미 인코딩된 값을 감싸는 좁은 문" 으로 정당화했지만, 그 문이 공개되어 있으면 호출자가 평문을 그대로 `ofEncoded(rawString)` 로 감싸 정책 게이트를 우회할 수 있다 — 설계 의도가 무너진다. 차라리 안 두는 편이 안전.
- **인코더 추상화.** `PasswordEncoder` 인터페이스를 도메인에 두고(`domain/user/PasswordEncoder.kt`), 인프라가 `BCryptPasswordEncoderAdapter` 로 구현. 도메인이 Spring Security 의존을 직접 끌어들이지 않는다. 테스트는 `FakePasswordEncoder` 로 BCrypt 비용을 우회.

### 트레이드오프 (감수)

`Password` 만 다른 VO 들과 다르게 `data class` 가 아니라 `class private constructor` + 수동 `equals/hashCode`. 일관성을 깨는 결정인데, **비밀번호는 다른 VO 와 같지 않다는 메시지를 코드 형태로 남기고 싶었다.** 데이터 클래스의 자동 `toString` 이 평문/인코딩 값을 노출할 위험을 차단하려면 명시적으로 오버라이드해야 하고, 그러면 `data class` 의 매력이 사라진다.

---

## 3. 고민 — "비밀번호에 생년월일 금지" 같은 교차 검증은 어디에?

### 문제

`Password` 는 자기 자신의 raw 만으로는 정책을 다 검증하지 못한다. **생년월일 미포함 규칙**은 `BirthDate` 를 알아야 한다. 그렇다고 `Password` 가 `User` 를 알게 만들 수도 없다(`User` 는 아직 없다 — `User.create` 가 `Password.ofRaw` 를 호출하는 입장).

### 검토한 대안

| 대안 | 검증 위치 | 단점 |
|---|---|---|
| (A) `User.create()` 안에서 `birthDate.compact()` 가 password 에 포함되는지 검사 | 도메인 진입 | `Password` VO 가 자기 정책을 모른다 — 정책이 분산됨 |
| (B) `Password.ofRaw(raw)` 는 자체 정책만 검증, "생년월일 금지" 는 별도 도메인 서비스로 분리 | 외부 정책 | 단일 규칙 하나 때문에 클래스 추가 — 과한 추상화 |
| **(C) `Password.ofRaw(raw, birthDate, encoder)` 시그니처에 생년월일 명시** | `Password` 자신 | `Password` 가 `BirthDate` 에 의존 |

### 선택: (C) — `Password.ofRaw` 가 `BirthDate` 를 받음

이유:
- **정책의 응집도**가 클래스 결합도보다 가치가 높다. "비밀번호에 무엇이 들어가면 안 되는가" 는 `Password` 가 책임지는 게 자연스럽다.
- `BirthDate → Password` 의존 방향이 단방향이다 — `BirthDate` 는 `Password` 를 모른다. 순환 없음.
- 호출자 입장에서 시그니처가 곧 문서다. `Password.ofRaw(raw, birthDate, encoder)` 만 봐도 "비밀번호 만들 때 생년월일이 필요한 어떤 규칙이 있다" 는 사실이 드러난다.

### 부수 결과 — `BirthDate.compact()` 라는 표현 메서드

`BirthDate` 는 두 가지 표현이 필요해졌다.
- ISO 표현 `2000-01-01` (응답 직렬화용 → `isoString()`)
- 압축 표현 `20000101` (비밀번호 포함 검사용 → `compact()`)

VO 가 자신의 표현 변환을 직접 갖는 패턴을 일찍 굳혔다. `Name.masked()`, `PhoneNumber.masked()` 도 같은 결.

---

## 4. 고민 — 매 요청마다 헤더로 인증, 어떻게 컨트롤러를 깨끗하게 둘까

### 문제

요구사항: 내 정보 조회와 비밀번호 수정은 매 요청마다 `X-Loopers-LoginId` / `X-Loopers-LoginPw` 헤더로 인증. 이걸 컨트롤러마다 `request.getHeader(...)` 로 풀면 같은 보일러플레이트가 반복되고, 헤더 누락 처리도 곳곳에 흩어진다.

### 검토한 대안

| 대안 | 어디서 처리 | 컨트롤러 시그니처 | 보안 단방향 |
|---|---|---|---|
| (A) `@RequestHeader` 두 개 직접 받기 | 컨트롤러 | `@RequestHeader(...) loginId, @RequestHeader(...) password` 2개 파라미터 | 누락 검증 매번 |
| (B) `OncePerRequestFilter` + `SecurityContext` | 필터 | `Authentication principal` | Spring Security 의존성 추가 |
| (C) `HandlerInterceptor` | 인터셉터 | 컨트롤러는 `request` 직접 파싱 | 핸들러 시그니처에 자연스럽지 않음 |
| **(D) `HandlerMethodArgumentResolver`** | 리졸버 | `credentials: LoginCredentials` 한 파라미터 | 누락 시 `UNAUTHORIZED` 한 곳 |

### 선택: (D) — `LoginCredentialsArgumentResolver`

```kotlin
data class LoginCredentials(val loginId: LoginId, val rawPassword: String) {
    companion object {
        const val LOGIN_ID_HEADER = "X-Loopers-LoginId"
        const val LOGIN_PW_HEADER = "X-Loopers-LoginPw"
    }
}

@Component
class LoginCredentialsArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(p: MethodParameter) = p.parameterType == LoginCredentials::class.java
    override fun resolveArgument(...): LoginCredentials {
        val loginId = req.getHeader(LOGIN_ID_HEADER) ?: throw CoreException(UNAUTHORIZED, "$LOGIN_ID_HEADER 헤더가 필요합니다.")
        val rawPassword = req.getHeader(LOGIN_PW_HEADER) ?: throw CoreException(UNAUTHORIZED, "$LOGIN_PW_HEADER 헤더가 필요합니다.")
        return LoginCredentials(LoginId(loginId), rawPassword)
    }
}
```

이유:
- 컨트롤러가 깨끗해진다.

  ```kotlin
  @GetMapping("/me")
  fun getMyInfo(credentials: LoginCredentials) = ...
  ```
- Spring Security 를 끌어들이지 않는다. Round 1 의 인증은 매 요청마다 헤더로 ID/PW 를 보내는 단순 모델이라, Security 의 SecurityContext·필터 체인은 과하다.
- 헤더 누락 처리가 한 곳(`resolveArgument`)에 집중된다.
- `LoginId` 형식 위반(영문·숫자 4~20자)은 `LoginId(value)` 의 `init` 이 `BAD_REQUEST` 로 던져준다. 즉 헤더 누락은 `UNAUTHORIZED`, 헤더 형식 오류는 `BAD_REQUEST` 로 자연 분리.

### 트레이드오프

- **인증 책임이 비즈니스 레이어로 새어 들어간다.** `LoginCredentialsArgumentResolver` 는 헤더가 있는지만 본다 — 비밀번호 일치 여부는 `UserService.authenticate()` 가 본다. 두 단계로 쪼개진 형태가 어색해 보일 수 있지만, "표현 계층은 입력 형식만, 응용 계층은 자격 검증" 이라는 분리는 합리적이다.
- 향후 토큰 기반 인증(JWT, 세션)이 들어오면 이 리졸버를 다른 리졸버로 갈아끼우거나 추상화해야 한다 — 그러나 그 시점은 멀고, 지금 일반화는 과적합이다.

---

## 5. 고민 — 마스킹은 누구의 책임인가

### 문제

요구사항: 이름은 `홍길*`, 휴대폰은 `010-****-5678` 로 마스킹해 응답.
이걸 응답 DTO 에서 String 처리할 수도, 도메인 VO 에서 마스킹 메서드를 가질 수도 있다.

### 검토한 대안

| 대안 | 마스킹 위치 | 책임 흐름 | 단점 |
|---|---|---|---|
| (A) `UserResponse` 에서 String 가공 | 표현 계층 | 도메인은 모름 | 마스킹 정책 변경 시 표현마다 흩어짐. 다른 응답 채널(SMS 안내 등)에서 또 작성 |
| **(B) VO 에 `masked()` 메서드 + 응용 계층 `UserInfo` 가 양쪽 표현 보유** | 도메인 VO + 응용 계층 결과 | 마스킹은 VO, 어떤 필드를 마스킹할지는 응용 계층 | VO 가 표현 책임 일부 가짐 |

### 선택: (B)

```kotlin
@Embeddable
data class Name(@Column(...) val value: String) {
    fun masked(): String =
        when {
            value.length <= 1 -> "*"
            else -> value.dropLast(1) + "*"
        }
}

@Embeddable
data class PhoneNumber(@Column(...) val value: String) {
    fun masked(): String {
        val parts = value.split("-")
        return "${parts[0]}-****-${parts[2]}"
    }
}

// application/user/UserInfo.kt — 양쪽 표현을 모두 들고 있다
data class UserInfo(
    val loginId: String,
    val maskedName: String,         // ← VO.masked() 결과
    val birthDate: String,
    val email: String,
    val maskedPhoneNumber: String,  // ← VO.masked() 결과
)
```

이유:
- "이름의 마지막 글자를 마스킹한다" 는 규칙은 `Name` 의 본질적 표현이다 — VO 에 두는 것이 응집도가 높다.
- 응용 계층 `UserInfo` 는 "이번 응답에 어떤 표현을 쓸지" 를 결정 — 이름·휴대폰은 마스킹된 표현, 이메일·생년월일은 원본. 이건 정책이고 응용 계층의 책임.
- 표현 계층 `UserResponse` 는 그저 매핑만 한다 — `info.maskedName` 을 `name` 필드로 옮기는 식. 표현 계층이 도메인 String 을 손대지 않는다.

### 따라온 작은 결정

`UserInfo` 의 필드명을 `maskedName`, `maskedPhoneNumber` 로 명시했다 — `name` 이 아니라. **이 객체에 들어 있는 값의 의미를 변수명에서부터 드러내기 위함이다.** `info.name` 이라고 하면 풀네임을 기대할 수 있지만, `info.maskedName` 은 마스킹된 값이라는 사실을 호출자가 헷갈릴 여지가 없다.

---

## 6. 고민 — "없는 사용자" 와 "비밀번호 틀림" 을 구분해서 응답할 것인가

### 문제

`getMyInfo` 가 호출됐는데:
- (i) 해당 `loginId` 가 DB 에 없다 → 404 NotFound? 401 Unauthorized?
- (ii) `loginId` 는 있지만 비밀번호가 틀리다 → 401 Unauthorized

(i) 을 NotFound 로 응답하면 **계정 enumeration** 이 가능해진다 — 공격자가 임의 ID 들을 던져보면서 NotFound vs Unauthorized 응답 차이로 어떤 ID 가 가입되어 있는지 알아낼 수 있다.

### 선택: 둘 다 `UNAUTHORIZED`, 메시지도 동일

```kotlin
private fun authenticate(loginId: LoginId, rawPassword: String): User {
    val user = userRepository.findByLoginId(loginId)
        ?: throw CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")
    user.authenticate(rawPassword, passwordEncoder)
    return user
}

// User.authenticate 도 같은 메시지로 던진다
fun authenticate(rawPassword: String, encoder: PasswordEncoder) {
    if (!password.matches(rawPassword, encoder)) {
        throw CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")
    }
}
```

이유:
- **존재 노출 방지**가 우선이다. NotFound 도 Forbidden 도 아닌 `UNAUTHORIZED` 단일 응답.
- 메시지도 동일하게 통일 — "ID 또는 비밀번호" 라는 모호한 표현이 의도된 모호함이다.
- 테스트에 명시적으로 박혀있다(`UserServiceTest.shouldReturnUnauthorized_whenUserNotFound`) — 회귀 방지용 가드.

이건 사실 보안 베스트 프랙티스라 고민이라기보단 "잊지 않고 박은 결정" 에 가깝다. 그러나 테스트 케이스 이름에 *(존재 노출 금지)* 를 넣어둔 건, 누가 미래에 "더 친절한 메시지로 바꾸자" 며 분리하려 할 때 이유를 남기기 위함이다.

---

## 7. 고민 — `User` 객체의 안전한 생성 진입점

### 문제

회원가입은 반드시 두 단계를 통과해야 한다.
1. 비밀번호 정책 검증 + 인코딩 (`Password.ofRaw` 호출)
2. 로그인 ID 중복 검사 (`UserService.signUp` 책임)

(1) 단계가 우회되면 평문이 DB 에 들어가거나 정책을 어긴 비밀번호가 통과한다. `User(loginId, password, ...)` 같은 1차 생성자를 외부에 열어두면 호출자가 (1) 을 깜빡할 수 있다.

### 검토한 대안

| 대안 | 의미 | 단점 |
|---|---|---|
| (A) `protected constructor` + `companion.create()` | "외부에서 직접 만들지 말고 create 거쳐라" | `User` 가 `final` 클래스인데 protected 는 사실상 private — IDE 가 *protected visibility is effectively private in a final class* 경고 |
| (B) `private constructor` + `companion.create()` | 가장 강한 보호 | 테스트가 `User(...)` 직접 못 만듦 → 매 테스트가 `create()` + 인코더 fake 를 통과해야 함 |
| **(C) `internal constructor` + `companion.create()`** | 같은 모듈 내부만 허용 | (없음 — 다른 자매 모듈은 차단됨) |
| (D) `public constructor` | 참조 프로젝트 컨벤션 | 향후 `stay-batch`/`stay-admin` 같은 모듈이 보안 게이트 우회 가능 |

### 선택: (C) — `internal constructor`

```kotlin
@Entity
@Table(name = "users", uniqueConstraints = [UniqueConstraint(name = "uk_users_login_id", columnNames = ["login_id"])])
class User internal constructor(
    loginId: LoginId, password: Password, name: Name,
    birthDate: BirthDate, email: Email, phoneNumber: PhoneNumber,
) : BaseEntity() {
    ...
    companion object {
        fun create(
            loginId: LoginId, rawPassword: String, name: Name,
            birthDate: BirthDate, email: Email, phoneNumber: PhoneNumber,
            encoder: PasswordEncoder,
        ): User = User(
            loginId = loginId,
            password = Password.ofRaw(rawPassword, birthDate, encoder),
            name = name, birthDate = birthDate, email = email, phoneNumber = phoneNumber,
        )
    }
}
```

이유:
- `User.create()` 는 단순 편의 팩토리가 아니라 **비밀번호 정책 검증과 인코딩을 강제로 통과시키는 보안 게이트** 다. 반드시 거쳐야 한다.
- `protected` 는 의미가 잘못됐다 — 누구도 `User` 를 상속하지 않는다.
- `private` 는 테스트 friction 이 크다. `UserTest` 가 다양한 상태의 `User` 를 만들 때마다 `create()` + 인코더 fake 통과 비용이 누적.
- `internal` 은 정확히 "이 모듈(=`apps/stay-api`) 내부 — production·test 는 허용, 다른 모듈은 차단" 을 표현한다.
- `public` 은 향후 `apps/stay-batch`, `apps/stay-admin` 같은 자매 모듈에서 보안 게이트를 우회할 여지를 남긴다. 미래 보호.

### 참조 프로젝트와의 의도적 차이

`loop-pack-be-l2-vol3-kotlin` 의 `example/ExampleModel` 은 평범한 `public constructor` 다 — `description` 이 빈문자열이 아닌가만 보는 단순한 도메인이라 그렇다. **stayloop 의 `User` 는 다르다**. 비밀번호라는 보안 자산을 다루므로 한 칸 더 보수적인 변종을 의식적으로 골랐다.

---

## 8. 고민 — 도메인을 영속 모델과 어떻게 묶을 것인가

### 문제

VO 들을 `@Embeddable` 로 만들고 나니, `domain.user.User` 와 `infrastructure.user.UserJpaEntity` 가 사실상 똑같은 필드 묶음을 두 번 갖는 모양이 된다. 그 사이에 `UserRepositoryAdapter` 가 변환 어댑터로 끼어 있다 — `toDomain/fromDomain`. 이 구조에서 거슬리는 점:

```kotlin
// 어댑터의 비밀번호 변경 흐름 — JPA 의 dirty checking 을 흉내내고 있다
override fun save(user: User): User =
    if (user.id == 0L) jpa.save(fromDomain(user)).toDomain()
    else jpa.findById(user.id).get().apply { password = user.password }.let { jpa.save(it).toDomain() }
```

매니지드 엔티티의 setter 한 번이면 끝나는 일을, "도메인 → 변환 → 매니지드 엔티티에 복사 → save" 4단계로 푸는 셈.

### 검토한 대안

| 대안 | 분리 | 어댑터 | 변환 비용 | dirty checking |
|---|---|---|---|---|
| (A) 도메인/JPA 완전 분리 + 어댑터 변환 | 강함 | 항상 | toDomain/fromDomain | 흉내 |
| (B) `User = @Entity` + 어댑터 유지 | 약함 | 선택적 | 없음 | 가능 |
| **(C) `User = @Entity` + 어댑터 제거 (인터페이스 다중 상속)** | 약함 | 없음 | 없음 | 가능 |

### 선택: (C) — `User` 가 곧 JPA Entity, 어댑터 제거

리포지토리는 인터페이스 다중 상속으로 어댑터를 흡수했다.

```kotlin
// 도메인 인터페이스 — 비즈니스 어휘로 정의
interface UserRepository {
    fun save(user: User): User
    fun findByLoginId(loginId: LoginId): User?
    fun existsByLoginId(loginId: LoginId): Boolean
}

// 인프라는 두 인터페이스를 동시에 만족시킨다
interface UserJpaRepository : UserRepository, JpaRepository<User, Long>
```

`JpaRepository.save(entity: T): T` 시그니처가 `UserRepository.save(user: User): User` 와 일치하므로 **한 메서드가 두 계약을 동시에 만족** 한다. Spring Data 가 빈을 자동 등록 → `@Component` 어댑터, 별도 클래스 모두 불필요.

### 결과

- `UserJpaEntity.kt` — 통째 삭제
- `UserRepositoryAdapter.kt` — 통째 삭제
- `User.reconstruct()` — 더 이상 변환 일이 없으므로 제거
- `UserService.changePassword()` — `findByLoginId → user.changePassword(...) → save` 흐름. 트랜잭션 안에서 매니지드 상태이므로 사실 `save` 호출 없이도 종료 시 UPDATE 가 자동 발행된다. `save` 는 명시성을 위해 남겼다.

### 부수 인프라 결정 — `kotlin-allopen` 플러그인

`var ... protected set` 으로 옮긴 직후 IDE 가 6줄에 걸쳐 *protected visibility is effectively private in a final class* 경고를 띄웠다. 코틀린 클래스는 기본 `final` 이라 `protected` 가 사실상 `private` 와 같다. **클래스가 `open` 이 되어야 `protected` 가 의미를 갖는다.**

| 대안 | 작업 | 일관성 | 미래 비용 |
|---|---|---|---|
| (A) 무시 | 0 | 보장 | 향후 lazy 연관관계 도입 시 깨짐 |
| (B) 클래스마다 `open` 키워드 | 엔티티마다 `open class` | 누락 위험 | 새 엔티티 추가마다 잊음 |
| **(C) `kotlin-allopen` 플러그인 + 어노테이션 지정** | 빌드 설정 1회 | 자동 보장 | 0 |

```kotlin
// build.gradle.kts (root)
subprojects {
    apply(plugin = "org.jetbrains.kotlin.plugin.allopen")
    extensions.configure<AllOpenExtension> {
        annotation("jakarta.persistence.Entity")
        annotation("jakarta.persistence.MappedSuperclass")
        annotation("jakarta.persistence.Embeddable")
    }
}
```

흔히 헷갈리는데 `kotlin-spring` 은 Spring 스테레오타입(`@Component`/`@Transactional`) 만, `kotlin-jpa` 는 no-arg 생성자만 처리한다 — `@Entity`/`@Embeddable` 을 자동 open 해주지 않는다. allopen 은 별개로 필요.

### 트레이드오프 (감수)

- **도메인 패키지에 `jakarta.persistence.*` 가 더 깊이 들어왔다.** `@Entity`, `@Embedded`, `@Table`, `BaseEntity` 가 모두 도메인에 있다. NoSQL/메모리 저장소로 이전하기는 어려워졌지만, 그 시나리오가 가까운 미래에 없다.
- **테스트 fake 가 까다로워졌다.** `User.reconstruct(id, ...)` 가 사라지면서 `InMemoryUserRepository` 는 `BaseEntity.id` 를 리플렉션으로 채워야 한다.

  ```kotlin
  private fun assignId(user: User, id: Long) {
      val field = BaseEntity::class.java.getDeclaredField("id")
      field.isAccessible = true
      field.setLong(user, id)
  }
  ```

  깨끗한 코드는 아니지만, **fake 의 책임은 JPA 의 id 자동 할당 동작을 흉내내는 것** 이고 그 동작 자체가 리플렉션 기반이다. 프로덕션 코드에 리플렉션이 새지 않는 한 fake 한 곳에 가둬두는 것은 합리적.

---

## 9. 고민 — 어떤 필드는 바뀔 것이고, 어떤 필드는 안 바뀐다 — 표현은?

### 문제

회원 도메인을 길게 보면 다음이 거의 확실히 추가된다.
- 이메일 변경(`changeEmail()`)
- 휴대폰 변경(`changePhoneNumber()`)
- 이름 변경(`changeName()`)

`val` 로 잠가두면 그 시점에 또 한 번 리팩토링 — `val → var` + setter 가시성 제어. `var` 로 두면 "외부에서도 바꿀 수 있어 보인다" 는 잘못된 인상.

### 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| `val` 유지 | 불변 표현, 의도 명확 | 도메인 메서드 추가 시마다 `var` 로 풀어야 함 |
| 필요한 것만 `var` (예: password) | 미니멀 | 일관성 부족 — 어떤 필드는 본문, 어떤 필드는 생성자에 |
| **모두 `var ... protected set`** | 일관성, 미래 확장성 | "수정 가능" 으로 보일 위험 — `protected set` 으로 차단 |

### 선택: 모두 `var ... protected set` (본문 선언)

```kotlin
class User internal constructor(
    loginId: LoginId, ...                    // 그냥 파라미터 (val/var 없음)
) : BaseEntity() {
    @Embedded var loginId: LoginId = loginId
        protected set
    @Embedded var password: Password = password
        protected set
    ...
}
```

핵심: **"외부에선 read-only, 내부에서만 mutable"**.
- `user.email` — 읽기 가능
- `user.email = newEmail` — 컴파일 에러 (외부 코드)
- 클래스 내부의 `changeEmail()` 메서드만 `email = newEmail` 가능

### 왜 본문 선언인가 (생성자가 아니라)

코틀린은 **주 생성자의 `var` 에 `protected set` 을 직접 못 붙인다.** `var loginId: LoginId protected set` 같은 syntax 가 없다. 본문에 풀어 선언하는 것이 setter 가시성을 제어하는 유일한 방법이다.

비용: 필드당 3줄 → 6줄. 받아들였다 — 캡슐화의 가치가 더 크다.

---

## 10. 고민 — 테스트는 어디까지, 어떤 더블로

### 테스트 피라미드

| 레벨 | 위치 | 더블 | 무엇을 검증 |
|---|---|---|---|
| **단위** | `domain/user/value/*Test.kt`, `domain/user/UserTest.kt` | (없음) | VO 형식 검증, 마스킹, `User.changePassword` 가 정책 통과시키는지 |
| **통합** | `application/user/UserServiceTest.kt` | `InMemoryUserRepository` (리플렉션 id 할당), `FakePasswordEncoder` (BCrypt 우회) | 가입 → 인증 → 변경 흐름 + 중복/존재 노출 금지/동일 비번 거절 |
| **E2E (슬라이스)** | `interfaces/api/user/UserControllerTest.kt` | `@WebMvcTest` + `@MockkBean UserService` | HTTP 바인딩, 헤더 인증, 검증 실패 매핑(BAD_REQUEST/CONFLICT/UNAUTHORIZED) |
| **컨텍스트** | `StayApiContextTest` | Testcontainers MySQL 8.0 | 부팅 + `@Embedded` 매핑이 실제 DDL 로 풀리는지 |

### 두 가지 테스트 더블의 역할

- **`InMemoryUserRepository`** — 통합 테스트가 H2 / Testcontainers 를 매번 띄우지 않게 하는 빠른 fake. 단점은 JPA 의 dirty checking·캐스케이드 같은 어떤 동작도 흉내 못 한다는 점. 그래서 매핑 자체는 컨텍스트 테스트로 따로 검증.
- **`FakePasswordEncoder`** — BCrypt 의 work factor 비용은 단위 테스트 100개를 1초에서 30초로 늘린다. fake 인코더는 평문 그대로 비교 — 빠르지만 보안적으론 무의미. **인코딩 정책 자체는 `Password` 의 단위 테스트가 검증** 하므로, 통합 테스트의 fake 는 흐름만 본다.

### 명시적으로 박은 회귀 가드

```kotlin
@DisplayName("getMyInfo() 는 존재하지 않는 사용자에 대해서도 UNAUTHORIZED 로 응답한다 (존재 노출 금지).")
```

미래에 누가 "더 친절한 NotFound 메시지로 바꾸자" 며 분리하려 할 때 이 테스트가 빨갛게 떠서 막는다. 테스트 이름의 괄호 보충 *(존재 노출 금지)* 가 그 의도의 흔적.

### 게이트 결과

```
ktlintCheck: PASS
test: 73 / 73 PASS  (Testcontainers MySQL 포함)
```

### 의도적으로 안 한 것

`@DataJpaTest` 단위 매핑 라운드트립 테스트는 추가하지 않았다. `StayApiContextTest` 가 이미 실제 MySQL 로 부팅하고, `UserControllerTest`/`UserServiceTest` 의 흐름이 매핑을 우회 검증한다. 추가 테스트의 marginal value 가 낮다 — 향후 `@OneToMany`/`@ManyToOne` 연관관계가 들어오면 그 시점에 도입.

---

## 11. 받아들인 비용 정리와 후속 작업 후보

### 받아들인 비용

| 항목 | 비용 | 정당화 |
|---|---|---|
| 도메인 패키지에 `jakarta.persistence.*` 임포트 | 헥사고날 순수성 양보 | 이 프로젝트의 규모와 미래 시나리오에서 합리적. JPA 친화적으로 모델링하지 않으면 Hibernate 가 깡통 |
| `InMemoryUserRepository` 가 리플렉션으로 id 할당 | "깨끗한 코드" 가 아님 | JPA id 자동 할당 동작 자체가 리플렉션 기반. fake 한 곳에 격리 |
| 필드당 6줄(`var x = x` + `protected set` 본문) | 코드 길이 | "외부 read-only / 내부 mutable" 일관성 확보 |
| 비밀번호 평문이 `String` 으로 메서드 시그니처를 통과 (`changePassword(currentRaw, newRaw, ...)`) | 평문 String 이 잠시라도 변수에 산다 | `Password` 인스턴스로 감싸려면 정책 검증을 통과해야 하는데, `currentRaw` 는 매칭만 할 뿐 정책 통과 객체일 필요 없음. 인스턴스화 강제는 과한 추상화 |

### 해소한 항목 (리뷰에서 짚힌 후)

| 항목 | 처음 의도 | 변경 후 |
|---|---|---|
| 회원가입 동시성 race window | `existsByLoginId` 사전조회 + DB unique 둘 다 두되, race 시 5xx → "낮은 빈도, 의도적 단순화" 로 둠 | `ApiControllerAdvice` 가 `DataIntegrityViolationException` 을 `CONFLICT` 로 매핑. UserControllerTest 에 회귀 테스트 추가 |
| `Password.ofEncoded(encoded)` 우회 팩토리 | "이미 인코딩된 값을 감싸는 좁은 문" 으로 정당화하며 둠 | 삭제. 공개되어 있으면 호출자가 평문 raw 를 그대로 `ofEncoded(rawString)` 로 감쌀 수 있어 정책 게이트가 무력화됨. JPA 하이드레이션은 리플렉션이라 공개 팩토리 불필요 |

### 후속 PR 후보 (이번 범위 밖)

- **`UserController` → `UserV1Controller` 네이밍, `UserV1ApiSpec` 인터페이스 + Swagger 메타데이터 분리**: 참조 프로젝트의 API 버저닝 컨벤션 정렬
- **DTO 파일 통합** (`UserV1Dto.kt` 단일 파일 + nested classes): 참조 컨벤션 정렬, 우선순위 낮음
- **`UserService` → `UserFacade` + 도메인 서비스 `UserAuthenticator` 분리**: 단일 도메인이라 우선순위 낮음
- **풀 E2E 테스트 1건**: `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` + `DatabaseCleanUp` 로 회원가입 전 흐름 한 번
- **Bean Validation 어노테이션 vs VO 검증 일관화**: 현재는 VO 가 형식 검증을 책임지지만, 일부 프로젝트 컨벤션은 컨트롤러 레이어 `@Valid` 를 선호. 팀 합의 후 재정렬 가능

---

## 12. 한 줄 요약

> Round 1 의 회원 요구사항을 풀어가며, **타입 자체가 검증된 사실을 보장하는 VO 패턴**, **평문 비밀번호가 클래스 외부로 새지 않는 보안 게이트**, **헤더 인증을 ArgumentResolver 한 곳으로 모은 컨트롤러**, **마스킹을 VO 의 책임으로 본 응집도** 를 골랐다. 부수적으로 도메인을 JPA 와 통합해 dirty checking 을 받을 자리를 마련했고, 그 대신 도메인이 영속성에 더 깊이 묶이는 비용은 의식적으로 감수했다.
