# PR-01 — User 도메인을 JPA Entity 로 통합한 과정과 선택의 근거

> 회원(User) 엔티티 설계와 영속성 매핑을 다루며 했던 고민, 검토한 대안, 최종 선택과 그 이유 그리고 남는 트레이드오프를 정리한다.
> 작업 결과: `apps/stay-api` 의 User 도메인 — 값 객체 6종을 `@Embeddable` 로 전환하고, 도메인 `User` 와 `UserJpaEntity` 를 하나로 합쳐 Rich JPA Entity 로 만들었다.

---

## 1. 출발점 — 왜 손을 댔나

기존 구조는 도메인과 JPA 가 깔끔하게 분리돼 있었다.

```
domain/user/
  User.kt              ← 순수 도메인 클래스 (val id, val ...)
  UserRepository.kt    ← 도메인 인터페이스
  value/
    Email.kt           ← @JvmInline value class
    LoginId.kt         ← @JvmInline value class
    ... (Name, BirthDate, PhoneNumber, Password)

infrastructure/user/
  UserJpaEntity.kt           ← @Entity, primitive 컬럼 (String email, ...)
  UserJpaRepository.kt       ← JpaRepository<UserJpaEntity, Long>
  UserRepositoryAdapter.kt   ← UserRepository 구현 + toDomain/fromDomain
```

문제점은 두 가지였다.

1. **JPA 의 장점이 죽는다.** Dirty checking, lazy loading 같은 JPA 의 핵심 기능을 활용하려면 도메인 객체 자체가 영속 컨텍스트의 시민이어야 한다. 변환 어댑터 사이에 두면 항상 `save()` 를 명시적으로 호출해야 하고, 매 호출마다 `findById → 필드 복사 → save` 같은 보일러플레이트가 생긴다 (`UserRepositoryAdapter.save()` 가 그랬다).
2. **값 객체가 진짜 컬럼 타입을 표현하지 못했다.** `Email` 은 `@JvmInline value class` 라 런타임에 `String` 으로 인라인된다. JPA 는 그 사실을 모른다. 그래서 `UserJpaEntity` 는 `String email` 을 들고 어댑터가 변환하는 구조였다 — 도메인 검증(Email 정규식)이 영속 경계를 통과할 때마다 다시 실행되는 비대칭이 있었다.

여기서 두 단계의 의사결정이 필요했다.

- 1단계: **값 객체를 `@JvmInline value class` → `@Embeddable` 로 바꿀까?**
- 2단계: **`User` 도메인을 그대로 둘까, 아니면 `UserJpaEntity` 와 합쳐 Rich JPA Entity 로 만들까?**

---

## 2. 1단계 — 값 객체를 `@Embeddable` 로

### 검토한 대안

| 대안 | 설명 | 장점 | 단점 |
|---|---|---|---|
| (A) 그대로 두기 | `@JvmInline value class` 유지 + UserJpaEntity 가 String 보유 + 어댑터에서 변환 | 도메인이 JPA 에 무지 | 값 객체가 컬럼 타입이 아님 — 변환 비용·이중 검증 |
| (B) `AttributeConverter` 추가 | `String ↔ Email` 변환기 등록 | 도메인 변경 최소 | 컨버터 N개 + 보일러플레이트, 임베디드 다중 필드(Money 같은 타입)엔 한계 |
| **(C) `@Embeddable` 로 전환** | 값 객체가 직접 JPA 매핑 가능한 임베디드 타입이 됨 | 도메인 = 영속 타입, 어댑터 변환 사라짐 | 도메인 레이어에 JPA 어노테이션 침투 |

### 선택: (C) `@Embeddable` 데이터 클래스

```kotlin
@Embeddable
data class Email(
    @Column(name = "email", nullable = false, length = 100)
    val value: String,
) {
    init {
        if (!REGEX.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "이메일 형식이 올바르지 않습니다.")
        }
    }
    ...
}
```

핵심 결정 사항:

- **`@JvmInline value class` 는 JPA 엔티티 필드로 못 쓴다.** Hibernate 가 리플렉션으로 필드 타입을 보고 매핑을 잡는데, value class 는 JVM 레벨에서 인라인되어 사라지므로 인식 불가. 검증 로직 유무와는 무관. 즉 JPA 와 함께 쓰려면 `@Embeddable` 이 사실상 유일한 선택.
- **`data class` 로 둬도 JPA 에서 동작한다.** `kotlin-jpa` 플러그인이 `@Embeddable` 에 합성 no-arg 생성자를 만들어주고, JPA 는 이를 통해 객체를 생성한 뒤 리플렉션으로 필드를 채운다. `init` 블록이 JPA 하이드레이션 시 실행되지 않는 점은 여기서 오히려 장점 — DB 의 정합성을 신뢰하고 외부 입력에서만 검증한다.
- **`@Column` 메타데이터를 어디에 둘 것인가.** 두 가지 선택지가 있었다.
    - 임베디드의 `value` 필드에 두기 (선택)
    - 엔티티의 `@Embedded` 필드에 `@AttributeOverride` 로 두기

  전자를 택했다. 이유: **컬럼명·길이는 값 객체의 본질적 속성**이다. `Email` 컬럼은 어디에 임베드되든 `email` 이라는 이름과 100자 길이를 갖는다. AttributeOverride 는 같은 임베디드를 한 엔티티에 여러 번 박을 때만 의미가 있는데, 여기선 그런 케이스가 없다.

### 트레이드오프 (감수)

도메인 레이어에 `jakarta.persistence.*` 임포트가 들어간다. **순수 도메인 옹호자는 싫어할 결정이다.** 그러나:
- 이 프로젝트는 헥사고날의 모든 룰을 엄격히 강제하기엔 작다.
- JPA 친화적으로 모델링하지 않으면 Hibernate 는 깡통이다.
- 도메인 검증 로직(`init` 의 정규식)은 어노테이션 의존과 독립이다.

→ "JPA 에 의존하는 도메인" 을 의식적으로 받아들였다.

### 동시에 처리한 작업

`UserJpaEntity` 의 primitive 매핑(`String email` 등)을 `@Embedded Email email` 로 일괄 교체. 어댑터의 `toDomain/fromDomain` 변환 코드가 단순해졌다(파라미터를 그대로 넘김).

---

## 3. 2단계 — User = JPA Entity 통합

### 결정 직전의 갈등

값 객체를 `@Embeddable` 로 바꾼 직후의 구조는 이랬다.

```
domain/user/User.kt        ← 순수 도메인 (id: Long, val 필드)
infrastructure/user/UserJpaEntity.kt  ← @Entity, @Embedded 값 객체 보유
                          ← toDomain() 으로 User 로 변환
infrastructure/user/UserRepositoryAdapter.kt
  - if (user.id == 0L) fromDomain(user)
    else jpa.findById(user.id).apply { password = user.password }
  - jpa.save(entity).toDomain()
```

여기서 사용자가 "이렇게 두면 JPA 의 장점을 못 살린다" 고 지적했다. 정확한 진단이었다.

- `apply { password = user.password }` — JPA 의 dirty checking 을 흉내내는 어댑터 로직. 진짜 JPA 라면 매니지드 엔티티의 setter 호출만으로 트랜잭션 종료 시 UPDATE 가 자동 발행된다.
- `toDomain/fromDomain` — 매번 모든 필드를 옮겨 담는다. 필드가 늘 때마다 변환 코드도 늘어난다.
- 두 클래스(`User`, `UserJpaEntity`)에 똑같은 도메인 메서드 시그니처가 사실상 두 번 나타난다.

### 검토한 대안

| 대안 | 분리 정도 | 어댑터 필요 | 변환 비용 |
|---|---|---|---|
| (A) 도메인/JPA 완전 분리 (이전 구조) | 강함 | 항상 | toDomain/fromDomain |
| (B) `User = @Entity` + 어댑터 유지 | 약함 | 선택적 | 없음 |
| **(C) `User = @Entity` + 어댑터 제거** | 약함 | 없음 | 없음 |

### 선택: (C) — User 가 곧 JPA Entity, 어댑터 제거

```kotlin
@Entity
@Table(name = "users", uniqueConstraints = [...])
class User internal constructor(
    loginId: LoginId,
    password: Password,
    name: Name,
    birthDate: BirthDate,
    email: Email,
    phoneNumber: PhoneNumber,
) : BaseEntity() {

    @Embedded var loginId: LoginId = loginId
        protected set
    @Embedded var password: Password = password
        protected set
    @Embedded var name: Name = name
        protected set
    @Embedded var birthDate: BirthDate = birthDate
        protected set
    @Embedded var email: Email = email
        protected set
    @Embedded var phoneNumber: PhoneNumber = phoneNumber
        protected set

    fun authenticate(rawPassword: String, encoder: PasswordEncoder) { ... }
    fun changePassword(currentRaw: String, newRaw: String, encoder: PasswordEncoder) { ... }

    companion object {
        fun create(...): User = User(
            loginId = loginId,
            password = Password.ofRaw(rawPassword, birthDate, encoder),
            ...
        )
    }
}
```

리포지토리는 인터페이스 다중 상속으로 어댑터를 흡수했다.

```kotlin
// 도메인 인터페이스는 그대로 유지
interface UserRepository {
    fun save(user: User): User
    fun findByLoginId(loginId: LoginId): User?
    fun existsByLoginId(loginId: LoginId): Boolean
}

// 인프라는 두 인터페이스를 동시에 만족시킨다
interface UserJpaRepository :
    UserRepository,
    JpaRepository<User, Long>
```

`JpaRepository.save(entity: T): T` 시그니처가 `UserRepository.save(user: User): User` 와 일치하므로 한 메서드가 두 계약을 모두 만족한다. Spring Data 가 자동으로 빈을 등록해주므로 `@Repository` 도, 별도 어댑터 클래스도 필요 없다.

### 결과: 삭제된 것들

- `UserJpaEntity.kt` — 통째로
- `UserRepositoryAdapter.kt` — 통째로
- `User.reconstruct()` — 더 이상 변환할 일 없음

### 결과: 단순해진 것들

- `UserService.signUp()` 흐름이 그대로다. `userRepository.save(user)` 가 신규면 INSERT, 매니지드면 dirty checking 으로 자동 UPDATE.
- `UserService.changePassword()` 도 자연스럽다 — `findByLoginId → user.changePassword(...) → save` 흐름인데, 트랜잭션 안에서 매니지드 상태이므로 사실 `save` 호출 없이도 종료 시 UPDATE 가 발행된다(`save` 는 명시성 차원에서 유지).

### 트레이드오프 (감수)

- **테스트용 in-memory 리포지토리가 까다로워졌다.** `User.reconstruct(id, ...)` 로 임의 id 를 부여하던 방식이 사라지면서, `InMemoryUserRepository` 는 `BaseEntity.id` 를 리플렉션으로 채워야 한다.

  ```kotlin
  private fun assignId(user: User, id: Long) {
      val field = BaseEntity::class.java.getDeclaredField("id")
      field.isAccessible = true
      field.setLong(user, id)
  }
  ```

  깨끗한 코드는 아니다. 그러나 **fake 의 책임은 JPA 의 id 자동 할당 동작을 흉내내는 것** 이고, 그 동작 자체가 리플렉션 기반이다. 프로덕션 코드에 리플렉션이 새지 않는 한, 테스트 fake 한 곳에 가둬두는 것은 합리적이다.
- **도메인이 영속성에 더 깊이 매여있다.** `@Entity`, `@Embedded`, `@Table`, `BaseEntity` 가 모두 도메인 패키지에 있다. 도메인을 다른 인프라(예: NoSQL, 메모리 저장소)로 이전하기가 더 어려워졌다. 그러나 그 시나리오는 가까운 미래에 없다.

---

## 4. 클래스 가시성 — `protected` vs `internal` vs `public`

### 검토한 대안

User 의 1차 생성자 가시성 결정. 검토 순서:

1. `protected constructor` — 첫 시도. 의도: "외부에서 직접 만들지 말고 `User.create()` 를 거쳐라."
    - **문제**: 상속 의도가 없는데 protected 를 쓰는 것은 의미가 비뚤어진다. IDE 가 정확하게 잡았다 — "protected visibility is effectively private in a final class."
2. `private constructor` — 가장 강한 보호. companion object 내부에서만 호출 가능.
    - 장점: 의도가 가장 분명.
    - 단점: 테스트에서 `User(...)` 직접 생성 불가 → `create()` 강제 → 인코더 fake 를 매번 통과해야 함.
3. **`internal constructor`** — 같은 모듈(Gradle 서브프로젝트) 내부만 허용. 최종 선택.
4. `public` — 참조 프로젝트(`loop-pack-be-l2-vol3-kotlin/.../example/ExampleModel`)의 컨벤션. 팀 규율에 의존.

### 선택: `internal constructor`

선택 이유:

- `User.create()` 는 단순한 편의 팩토리가 아니다. 비밀번호 정책 검증과 인코딩(`Password.ofRaw()`)을 강제로 통과시키는 보안 게이트다. **반드시 거쳐야 한다.**
- `protected` 는 의미가 잘못됐다 — 누구도 User 를 상속하지 않는다.
- `public` 은 다른 모듈에서 `User(loginId, password, ...)` 를 직접 호출할 여지를 남긴다 — 향후 `apps/stay-batch`, `apps/stay-admin` 같은 자매 모듈이 생기면 보안 게이트를 우회할 수 있다.
- `internal` 은 정확히 "이 모듈 내부에서만 — 즉 production code 와 같은 모듈의 테스트는 허용, 다른 모듈은 차단" 을 표현한다.

### 참조 프로젝트와의 의도적 차이

`example/ExampleModel` 은 `class ExampleModel(name: String, description: String)` — 가장 평범한 public 생성자다. `description` 빈문자열 같은 도메인 가드를 통과한 인스턴스만 만들어지면 되는 단순한 도메인이라 그렇다.

User 는 다르다. **비밀번호** 라는 보안 자산을 다룬다. 평문이 절대 클래스 외부에 노출되어선 안 되고, 인코딩되지 않은 Password 인스턴스가 만들어져선 안 된다. 이런 도메인엔 example 가이드보다 한 칸 더 보수적인 변종이 적절하다.

---

## 5. 모든 필드를 `var ... protected set` 으로

### 갈등의 핵심

```kotlin
// 처음 작성한 것
class User(
    @Embedded val loginId: LoginId,
    password: Password,
    @Embedded val name: Name,
    @Embedded val birthDate: BirthDate,
    @Embedded val email: Email,
    @Embedded val phoneNumber: PhoneNumber,
) : BaseEntity() {
    @Embedded var password: Password = password protected set
}
```

`val` 은 "변하지 않는다" 를 표현한다 — 좋은 디폴트다. 하지만 **사용자가 정확히 짚었다**: "val 로 하면 사실상 나중에 수정 못 하잖아?"

회원 도메인에선 향후 거의 확실히 다음이 추가된다.
- 이메일 변경(`changeEmail()`)
- 휴대폰 변경(`changePhoneNumber()`)
- 이름 변경(`changeName()`)

`val` 로 잠가두면 그 시점에 또 한 번 리팩토링이 필요하다. 그리고 그 변경은 드물지 않다.

### 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| `val` 유지 | 불변 표현, 의도 명확 | 도메인 메서드 추가 시마다 `var` 로 풀어야 함 |
| 필요한 것만 `var` | 미니멀 | 일관성 부족 — 어떤 필드는 본문, 어떤 필드는 생성자에 |
| **모두 `var ... protected set`** | 일관성, 미래 확장성 | "수정 가능" 으로 보일 위험 |

### 선택: 모두 `var ... protected set`

참조 프로젝트의 `Brand`, `Product`, `Order` 가 모두 동일 패턴을 쓴다. 검증된 관용구다.

핵심은 **"외부에선 read-only, 내부에서만 mutable"**:
- `user.email` — 읽기 가능
- `user.email = newEmail` — 컴파일 에러 (외부 코드)
- 클래스 내부의 `changeEmail()` 메서드만 `email = newEmail` 가능

### 왜 본문 선언인가 (생성자가 아니라)

```kotlin
class User(
    loginId: LoginId,                  // 그냥 파라미터 (val/var 없음)
    ...
) : BaseEntity() {
    @Embedded var loginId: LoginId = loginId  // 진짜 프로퍼티
        protected set
}
```

코틀린은 **주 생성자의 `var` 에 `protected set` 을 직접 못 붙인다.** 즉 `var loginId: LoginId protected set` 같은 syntax 는 없다. 본문에 풀어 선언하는 것이 setter 가시성을 제어하는 유일한 방법이다.

비용: 코드가 길어진다(필드 1개당 3줄 → 6줄). 받아들였다 — 캡슐화의 가치가 더 크다.

---

## 6. `open` 과 `kotlin-allopen` 플러그인

### 발생한 IDE 경고

`var ... protected set` 으로 옮긴 직후 IDE 가 6줄에 걸쳐 경고를 띄웠다.

```
'protected' visibility is effectively 'private' in a final class
```

코틀린 클래스는 기본 `final` 이다. `final` 인 클래스는 자식이 없으므로, `protected` 의 의미("자기 자신 + 자식") 가 사실상 `private` 와 같아진다. 즉 IDE 는 정확히 짚었다 — **클래스가 open 이 되어야 protected 가 의미를 갖는다.**

### 검토한 대안

| 대안 | 작업 | 일관성 | 미래 비용 |
|---|---|---|---|
| (A) 무시 | 0 | 보장 | 향후 lazy 연관관계 도입 시 깨짐 |
| (B) 클래스마다 `open` 키워드 | 엔티티마다 `open class` | 누락 위험 | 같은 작업 N번 반복 |
| **(C) `kotlin-allopen` 플러그인** | 빌드 설정 1회 | 자동 보장 | 0 |

### 선택: (C) `kotlin-allopen`

```kotlin
// settings.gradle.kts
"org.jetbrains.kotlin.plugin.allopen" -> useVersion(kotlinVersion)

// build.gradle.kts (root)
plugins {
    kotlin("plugin.allopen") apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.plugin.allopen")

    extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
        annotation("jakarta.persistence.Entity")
        annotation("jakarta.persistence.MappedSuperclass")
        annotation("jakarta.persistence.Embeddable")
    }
}
```

플러그인 적용 후:
- `@Entity` 가 붙은 모든 클래스가 컴파일 시점에 자동 `open` 처리 → IDE 경고 사라짐
- Hibernate 의 lazy 프록시(CGLIB / ByteBuddy 가 동적 서브클래스 생성) 가 정상 동작 가능
- 향후 `@OneToMany(fetch = LAZY)` 를 추가해도 이미 준비되어 있음

**클래스에 `open` 키워드를 직접 붙이는 것은 안티패턴**이다. 누락이 발생하고, 새 엔티티 추가 시마다 잊는다. 빌드 설정 1회로 일관성을 확보하는 것이 시니어의 길.

### 왜 `kotlin-spring` 만으로는 부족한가

| 플러그인 | 역할 | JPA 어노테이션 처리 |
|---|---|---|
| `kotlin("plugin.spring")` | Spring 스테레오타입(`@Component`, `@Transactional`, ...) 자동 open | ❌ |
| `kotlin("plugin.jpa")` | `@Entity`/`@Embeddable`/`@MappedSuperclass` 에 합성 no-arg 생성자 추가 | ❌ open 처리 안 함 |
| `kotlin("plugin.allopen")` + 설정 | 사용자가 지정한 어노테이션을 자동 open | ✅ |

흔히들 헷갈리는데 `plugin.jpa` 는 노아그(no-arg)만, allopen 은 별개다.

---

## 7. 리포지토리 구조 — 어댑터 vs 다중 상속

### 두 가지 패턴

**패턴 A: 참조 프로젝트의 정석 (`example/`)**
```kotlin
// 도메인
interface ExampleRepository { fun find(id: Long): ExampleModel? }

// 인프라
interface ExampleJpaRepository : JpaRepository<ExampleModel, Long>

@Component
class ExampleRepositoryImpl(
    private val jpa: ExampleJpaRepository,
) : ExampleRepository {
    override fun find(id: Long): ExampleModel? = jpa.findByIdOrNull(id)
}
```

**패턴 B: 다중 상속 (이번 PR 의 선택)**
```kotlin
interface UserRepository {
    fun save(user: User): User
    fun findByLoginId(loginId: LoginId): User?
    fun existsByLoginId(loginId: LoginId): Boolean
}

interface UserJpaRepository :
    UserRepository,
    JpaRepository<User, Long>
```

### 트레이드오프

| 측면 | 패턴 A (어댑터) | 패턴 B (다중 상속) |
|---|---|---|
| 보일러플레이트 | 어댑터 클래스 1개 추가 | 0 |
| 도메인 메서드 명명 자유도 | 자유 (`getActive`, `findOrThrow` 등) | Spring Data 컨벤션 강제 (`findByXxx`) |
| 추가 로직 자리 (예외 throw, 캐싱) | 어댑터에 자연스럽게 | 별도 서비스로 빼야 함 |
| 빈 등록 | `@Component` 명시 | Spring Data 가 자동 |

### 선택: 패턴 B

이번 User 도메인은 메서드가 단순하다 (`save`, `findByLoginId`, `existsByLoginId`). Spring Data 의 메서드 네이밍 규칙으로 충분히 표현된다. 어댑터를 두는 것은 위장된 위임 클래스를 한 단계 더 쌓는 것 — 의미 없는 보일러플레이트.

향후 다음과 같은 요구가 생기면 패턴 A 로 전환:
- `getActive(loginId): User` — 없으면 NotFound throw 하는 의미적 메서드
- 도메인 메서드명이 Spring Data 컨벤션과 충돌
- 캐싱·로깅 같은 횡단 관심사

지금은 단순한 게 옳다.

---

## 8. 검증 — 무엇으로 신뢰를 얻었나

### 테스트 피라미드 점검

- **단위 (domain)**: `EmailTest`, `LoginIdTest`, `NameTest`, `PhoneNumberTest`, `BirthDateTest`, `PasswordTest`, `UserTest` — 값 객체 검증 + User 도메인 메서드. 변환 후에도 공개 API 가 동일하므로 그대로 살았다.
- **통합 (application)**: `UserServiceTest` — `InMemoryUserRepository` 페이크로 흐름 검증. 페이크의 id 할당 방식만 리플렉션으로 변경.
- **E2E (interfaces.api)**: `UserControllerTest` — `@WebMvcTest` 슬라이스. HTTP 바인딩과 검증 실패 매핑 검증.
- **Spring 컨텍스트**: `StayApiContextTest` — Testcontainers MySQL 8.0 으로 실제 부트스트랩까지 통과. `@Embedded` 매핑이 실제 DDL 로 풀리는 것까지 검증된다.

### 게이트 결과

```
ktlintCheck: PASS
test (Testcontainers MySQL 포함): 73 / 73 PASS
```

### 남는 빈자리 — 의도적

`UserJpaEntity` 에 대한 명시적 `@DataJpaTest` 라운드트립 테스트는 추가하지 않았다. 이유:
- `StayApiContextTest` 가 이미 전체 컨텍스트 + 실제 MySQL 로 부팅된다 → 매핑 오류는 거기서 잡힌다
- `UserControllerTest` 와 `UserServiceTest` 의 통합 흐름이 사실상 매핑을 우회 검증한다
- 추가 테스트의 marginal value 가 낮다

향후 `@OneToMany`/`@ManyToOne` 같은 연관관계가 추가되면 그 시점에 도입하는 것이 효율적.

---

## 9. 참조 프로젝트 (`loop-pack-be-l2-vol3-kotlin`) 비교 — 의식적으로 다르게 한 것

`example/` 는 정식 레퍼런스 템플릿이다. stayloop 와 차이가 나는 지점은 모두 의식적이다.

| 영역 | 참조 (`example/`) | stayloop User | 사유 |
|---|---|---|---|
| 생성자 가시성 | `class ExampleModel(...)` 공개 | `internal constructor` | 비밀번호 보안 게이트 강제 |
| 팩토리 | 없음 (직접 생성자) | `companion object create()` | 비밀번호 인코딩 강제 |
| Service 위치 | `domain/example/ExampleService` (도메인) + `application/example/ExampleFacade` (응용) | `application/user/UserService` 단일 | 단일 도메인이라 분리 불필요 |
| Controller 네이밍 | `ExampleV1Controller` | `UserController` | (정렬 미완 — 후속 PR 권장) |
| ApiSpec 분리 | `ExampleV1ApiSpec` 인터페이스 + Swagger | 없음 | (정렬 미완 — 후속 PR 권장) |
| DTO 파일 | `ExampleV1Dto.kt` 단일 + nested | 분리된 파일 | (정렬 미완 — 후속 PR 권장) |
| 리포지토리 | `XxxRepositoryImpl` 어댑터 | 다중 상속(`UserJpaRepository : UserRepository, JpaRepository`) | 메서드 단순 — 어댑터 불필요 |

**핵심 원칙**: 참조 프로젝트의 컨벤션은 **출발점**이지 정답이 아니다. 도메인 특성(보안 자산을 다루는가, 단일/복합 도메인인가)에 따라 의식적으로 갈라야 한다.

---

## 10. 남는 트레이드오프와 후속 작업 후보

### 받아들인 비용

1. 도메인 패키지에 `jakarta.persistence.*` 임포트가 들어옴 — 헥사고날 순수성 양보
2. `InMemoryUserRepository` 가 리플렉션을 씀 — JPA id 자동 할당 흉내
3. `var ... protected set` 본문 선언으로 필드당 6줄 — 캡슐화 비용

### 후속 PR 후보 (이번 범위 밖)

- **Controller / DTO 네이밍 정렬**: `UserController` → `UserV1Controller`, DTO 파일 `UserV1Dto.kt` 통합
- **`UserV1ApiSpec` 인터페이스 도입**: Swagger 메타데이터 분리
- **`@Nested inner class` 테스트 그룹핑**: `Create`, `Authenticate`, `ChangePassword` 별로 묶기
- **`UserService` → `UserFacade` 개명** (또는 도메인 서비스 `UserAuthenticator` 분리): 참조의 레이어 컨벤션 정렬. 단일 도메인이라 우선순위 낮음.
- **풀 E2E 테스트 1건 추가**: `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` + `DatabaseCleanUp` 조합 1건만 — 회원가입 전 흐름 검증

---

## 11. 한 줄 요약

> 값 객체를 `@Embeddable` 로 끌어올리고 `User` 가 곧 JPA Entity 가 되도록 합쳤다. 변환 어댑터·`reconstruct` 팩토리·`fromDomain/toDomain` 보일러플레이트가 사라졌고, JPA 의 dirty checking·연관관계 lazy loading 같은 미래의 기능을 받을 자리를 마련했다. 받아들인 비용은 도메인이 영속성에 더 깊이 묶인다는 것 — 그러나 이 프로젝트의 규모와 미래 시나리오에서 그 비용은 합리적이다.
