---
name: verify-architecture
description:
  Stayloop 기능 구현/리팩토링 직후, 테스트 실행 전에 아키텍처 정합성을 검수하는 게이트.
  계층 의존 방향(domain ← application ← interfaces.api / infrastructure), Aggregate 패키지 구성·명명 규약,
  트랜잭션 경계, Repository 인터페이스 위치, DTO/JPA 어노테이션 누출, 멀티모듈 경계를 점검한다.
  코드를 새로 작성하거나 리팩토링하지 않으며, 위반·이탈을 드러내고 개선 선택지를 제시한다.
  verify-tests 보다 먼저 호출되어 구조 결함을 거른다. 본 스킬이 FAIL 인 동안 verify-tests 는 의미가 없다.
user-invocable: true
---

Stayloop 의 모든 기능 구현/리팩토링은 **이 스킬을 통과한 뒤에야 verify-tests 로 넘어간다.**
이 스킬은 **검증자의 관점**으로 동작하며, 코드를 새로 짜주지 않고 **구조 위반·명명 이탈·계층 침범**을 식별한다.

---

### 0️⃣ 컨텍스트 수집

검증을 시작하기 전 다음을 명시적으로 확인한다. 추측하지 말고, 모르는 부분은 개발자에게 한 번에 묻는다.

- 이번 변경의 단위는 무엇인가? (신규 Aggregate / 기존 Aggregate 수정 / 횡단 관심사)
- 어떤 패키지·모듈이 추가/이동/삭제되었는가?
- 새 외부 시스템 (DB·Redis·Kafka·외부 API) 어댑터가 도입되었는가?

> 출력: 검증 대상 패키지/모듈을 한 줄로 요약한 뒤 본격적인 점검을 시작한다.

---

### 1️⃣ 계층 의존 방향 점검 (가장 중요)

의존은 **항상 안쪽**(domain) 으로만 흘러야 한다. 다음 import 가 있으면 즉시 **FAIL**.

| 위치 | 금지된 import |
|---|---|
| `com.stayloop.domain.**` | `com.stayloop.application.**`, `com.stayloop.infrastructure.**`, `com.stayloop.interfaces.**`, `org.springframework.web.**`, `jakarta.servlet.**` |
| `com.stayloop.application.**` | `com.stayloop.infrastructure.**`, `com.stayloop.interfaces.**`, `org.springframework.web.**` |
| `com.stayloop.interfaces.api.**` | `com.stayloop.infrastructure.**` (Repository 직접 호출 금지) |
| `com.stayloop.infrastructure.**` | `com.stayloop.interfaces.**` |

확인 방법(예):
```
Grep "^import com\.stayloop\.(application|infrastructure|interfaces)\." in apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import com\.stayloop\.(infrastructure|interfaces)\."        in apps/stay-api/src/main/kotlin/com/stayloop/application
```

> 단순한 stdlib/value-object import 외에 위 표를 어기는 라인이 한 줄이라도 있으면 FAIL 로 표기한다.

---

### 2️⃣ Aggregate 패키지 구성·명명 점검

각 Aggregate (예: `user`, 향후 `reservation` 등) 는 다음 구조를 따른다. 이 구조에서 벗어나면 보완 대상.

```
domain/{aggregate}/
  {Aggregate}Model.kt          # JPA 엔티티 (@Entity 는 여기에만)
  {Aggregate}Repository.kt     # 도메인 Repository 인터페이스
  {Aggregate}Service.kt        # 도메인 서비스 (선택)
  value/...                    # 값 객체 (@Embeddable)

application/{aggregate}/
  {Aggregate}Facade.kt         # @Service, @Transactional 경계
  {Aggregate}Info.kt           # 출력 DTO (도메인 모델이 외부로 새지 않게)
  command/...                  # 입력 Command

interfaces/api/{aggregate}/
  {Aggregate}V1Controller.kt   # @RestController, V1ApiSpec 구현
  {Aggregate}V1ApiSpec.kt      # @Tag + @Operation (springdoc)
  {Aggregate}V1Dto.kt          # 요청/응답 DTO를 중첩 data class 로 모음

infrastructure/{aggregate}/
  {Aggregate}JpaRepository.kt   # extends JpaRepository<{Aggregate}Model, Long> 만
  {Aggregate}RepositoryImpl.kt  # @Component, 도메인 Repository 인터페이스 구현 + JpaRepository 위임
  {Aggregate}{Adapter}.kt       # 외부 포트 어댑터 (예: BCryptPasswordEncoderAdapter)
```

체크 항목:

- 엔티티 이름은 `{Aggregate}Model` 인가? (예: `UserModel`. 단순 `User` 는 이탈)
- `{Aggregate}V1Controller` 가 `{Aggregate}V1ApiSpec` 인터페이스를 구현하는가?
- 요청/응답 DTO 가 `{Aggregate}V1Dto` 의 중첩 `data class` 로 묶여 있는가? (`dto/` 하위 분산은 이탈)
- 도메인 Repository 인터페이스가 `domain/{aggregate}/` 에 있는가? (infrastructure 에 있으면 이탈)
- `{Aggregate}JpaRepository` 가 도메인 Repository 인터페이스를 직접 상속하지 않는가? (`UserJpaRepository : UserRepository, JpaRepository<...>` 는 금지 — Impl 로 분리)
- 입력은 `command/`, 출력은 `Info` 로 정리되어 있는가?

---

### 3️⃣ 어노테이션·기술 누출 점검

| 어노테이션 / 기술 | 허용 위치 |
|---|---|
| `@Entity` `@Table` `@Embeddable` `@Embedded` | **`domain` 에서만** |
| `@RestController` `@RequestMapping` `@*Mapping` | **`interfaces.api` 에서만** |
| `@Service` (Facade) | `application` |
| `@Service` (도메인 서비스) | `domain` 허용 (Spring stereotype 의존은 약함) |
| `@Component` | `infrastructure` (어댑터/Repository 구현) |
| `@Transactional` | **`application` 의 Facade 에서만** (domain·interfaces 금지) |
| `JpaRepository` | **`infrastructure` 에서만** |
| `org.springframework.web.*` | **`interfaces.api` 에서만** |

확인 방법:
```
Grep "@Transactional" in apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "@Transactional" in apps/stay-api/src/main/kotlin/com/stayloop/interfaces
Grep "@Entity|@Table"  in apps/stay-api/src/main/kotlin/com/stayloop/{application,infrastructure,interfaces}
Grep "JpaRepository"   in apps/stay-api/src/main/kotlin/com/stayloop/{domain,application,interfaces}
```

위반 1건도 FAIL 사유.

---

### 4️⃣ 횡단 규칙 점검

- **DTO 누출 금지**: `application` / `domain` 코드가 `interfaces.api.**.*Dto` 또는 `*Request` / `*Response` 를 import 하지 않는다.
- **도메인 모델 누출 금지**: `interfaces.api` 컨트롤러가 `domain` 엔티티(`*Model`) 를 응답으로 반환하지 않는다 (반드시 `Info` → `V1Dto.*Response` 매핑).
- **Repository 직접 호출 금지**: `interfaces.api` 가 `Repository` / `JpaRepository` 를 주입받지 않는다.
- **트랜잭션 단일 진입**: 한 유스케이스의 `@Transactional` 은 Facade 한 곳에만 존재. 도메인 서비스에 중첩 선언 금지.
- **예외 전략 일관성**: 도메인은 `CoreException(ErrorType, ...)` 으로만 실패를 알린다. `interfaces.api` 가 별도 RuntimeException 을 던지지 않는다.

---

### 5️⃣ 멀티모듈 경계 점검

`README.md` 의 모듈 정책에 따른다.

- `apps` → `modules`, `supports` 의존 OK. 역방향 금지.
- `modules`, `supports` 는 도메인(`com.stayloop.domain.*`) 을 알지 않는다 (`BaseEntity` 같은 reusable 공통은 예외).
- `modules` 사이의 상호 의존은 신규 도입 시 반드시 정당화.
- `build.gradle.kts` 의 `implementation(project(":..."))` 라인이 위 규칙을 어기는지 확인.

---

### 6️⃣ 빠른 자동 점검 (구조 grep)

스킬 내부에서 다음을 직접 실행해 1차 스크리닝한다 (예시; 실제 호출은 Grep 도구로):

```
# 의존 역류 의심
Grep "^import com\.stayloop\.(application|infrastructure|interfaces)\."   path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import com\.stayloop\.(infrastructure|interfaces)\."               path=apps/stay-api/src/main/kotlin/com/stayloop/application
Grep "^import com\.stayloop\.infrastructure\."                            path=apps/stay-api/src/main/kotlin/com/stayloop/interfaces

# 어노테이션 누출
Grep "@Transactional"   path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "@Entity|@Table"   path=apps/stay-api/src/main/kotlin/com/stayloop/application
Grep "@Entity|@Table"   path=apps/stay-api/src/main/kotlin/com/stayloop/interfaces
Grep "JpaRepository"    path=apps/stay-api/src/main/kotlin/com/stayloop/domain

# 명명 이탈
Glob "apps/stay-api/src/main/kotlin/com/stayloop/domain/**/*.kt"   # *Model / *Repository / *Service / value/* 외 의심
Glob "apps/stay-api/src/main/kotlin/com/stayloop/interfaces/api/**/*Controller.kt"   # V1 접미사 누락
Glob "apps/stay-api/src/main/kotlin/com/stayloop/interfaces/api/**/dto/**"           # dto 하위 패키지 잔존 여부
```

명령은 예시일 뿐이며, 결과를 사람의 판단으로 해석한다.

---

### 7️⃣ 출력 포맷

```markdown
## verify-architecture 결과: {기능명}

### 컨텍스트
- 변경 패키지/모듈: ...
- 신규 외부 어댑터: 있음/없음

### 의존 방향
- domain → application/infrastructure/interfaces 역참조: 0건 / N건 (위치)
- application → infrastructure/interfaces 역참조: 0건 / N건

### Aggregate 구조
- 명명 이탈: ...
- 파일 배치 이탈: ...

### 어노테이션 누출
- ...

### 횡단 규칙
- DTO/도메인 모델 누출: ...
- @Transactional 위치: ...

### 멀티모듈 경계
- ...

### 게이트 결정
- ✅ PASS — verify-tests 진행 가능
- ❌ FAIL — 다음 항목 보완 필요: 1) ... 2) ... 3) ...
```

---

### 8️⃣ 톤 & 원칙

- **코드를 직접 수정하지 않는다.** 위반을 적시하고 어떻게 고칠지는 개발자가 결정한다.
- 100% 순수성을 강요하지 않는다. 단, 이탈은 **이유와 함께** 명시되어야 PASS.
- FAIL 시 verify-tests 호출 전에 이 스킬을 다시 통과시키도록 안내한다.
- 한국어 응답을 기본으로 한다.
