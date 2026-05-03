---
name: verify-code
description:
  Stayloop 기능 구현/리팩토링 직후, verify-architecture 보다 먼저 호출되어 코드 자체의 결함을 잡는 게이트.
  Copilot/시니어 리뷰가 자주 지적하는 패턴 — 불변식 보호 누락, null 일관성, 자식 entity ID 동기화,
  외부 라이브러리 누출, 컬렉션 정렬 결정성, 캐시-원본 불일치, 입력 검증 누락, 멱등 깨짐, 사일런트 디폴트,
  동시성 사고, 자원 누수, 시간/Clock 의존, 예외 삼킴, 매직 상수, 코드 중복, 가시성 이탈,
  성능 함정(N+1/eager), 보안(시크릿 노출/주입) — 을 점검한다.
  코드를 새로 작성하거나 리팩토링하지 않으며, 결함을 드러내고 개선 선택지를 제시한다.
  자동 호출 순서: **verify-code → verify-architecture → verify-tests**.
  본 스킬이 FAIL 인 동안 verify-architecture / verify-tests 는 의미가 없다 — 코드 본문에 사일런트 사고가 남기 때문.
user-invocable: true
---

Stayloop 의 기능 구현/리팩토링은 **이 스킬을 통과한 뒤에야 verify-architecture 로 넘어간다.**

세 게이트의 역할 분담:

| 게이트 | 본다 |
|---|---|
| **verify-code** (본 스킬) | **코드 본문**의 사일런트 결함 — 불변식 / null / 동시성 / 자원 / 시간 / 예외 / 가독성 / 보안 |
| verify-architecture | 계층 의존 방향 / 패키지 명명 / 어노테이션 누출 / 멀티모듈 경계 |
| verify-tests | 테스트 피라미드 / 더블 사용 / 명세성 / 실패·경계 시나리오 / `./gradlew test && ktlintCheck` |

이 스킬은 **검증자의 관점**으로 동작하며, 코드를 새로 짜주지 않고 **위험한 패턴**을 식별한다.

---

### 0️⃣ 컨텍스트 수집

- 이번 변경의 단위는 무엇인가? (Aggregate / 모델 메서드 / Converter / Repository 시그니처 / Facade / 외부 어댑터)
- 어떤 레이어가 추가/수정되었는가?
- DB 컬럼 스키마(NOT NULL / UNIQUE / 자연 키)가 도메인 타입과 일관되는가?
- 외부 라이브러리(Spring Data, Jackson, Redis client, HTTP client, PG SDK 등) 가 새로 도입되었는가?
- 새 외부 호출(DB 외) 이 도입되었는가? (트랜잭션·재시도·타임아웃 검토 대상)

> 출력: 검증 대상 파일을 한 줄로 요약한 뒤 본격적인 점검을 시작한다.

---

### 1️⃣ Null 일관성 (Three-way Alignment)

도메인 타입 / DB 스키마 / 컨버터 시그니처 — **세 곳 모두** null 정책이 일치해야 한다.

| 확인 | 위반 예 |
|---|---|
| DB 컬럼 `nullable = false` 인데 컨버터가 `attribute: T?` 로 null 통과 | `convertToDatabaseColumn(null)` 이 `null` 또는 `"{}"` 반환 → 제약 위반 / 사일런트 디폴트 |
| 도메인 타입 non-null 인데 `convertToEntityAttribute` 가 `T?` 반환 | 호출자 NPE |
| 같은 코드베이스의 컨버터들이 null 정책이 다름 | 일관성 깨짐 |
| Kotlin 의 `T?` 와 DB `NULL` 의 의미가 코드 주석으로만 추론됨 | 의도가 코드로 드러나지 않음 |

**가드**: NOT NULL 컬럼 컨버터는 null 입력 즉시 `INTERNAL_ERROR`. NULL 허용이면 도메인 타입도 일관되게 `T?`.

---

### 2️⃣ 자식 Entity / Aggregate 내부 식별자 동기화

`@OneToMany` / `@OneToOne` 자식이 부모 PK 를 들고 있을 때 **영속화 전 부모 PK = 0** 이라는 사실이 자주 잊혀진다.

| 확인 | 위반 예 |
|---|---|
| `child = ChildModel(parentId = this.id, ...)` 형태 — 부모 영속화 전이면 0 으로 굳음 | 신규 부모의 자식들이 모두 `parentId=0` 으로 저장 |
| 자식의 `parentId` 컬럼과 부모의 `@JoinColumn` 이 동시에 쓰기 가능 | 두 곳이 같은 컬럼을 채우려고 경합 |
| `@OneToMany` 가 `cascade=ALL` 없이 부모 라이프사이클 분리 | 부모 삭제 시 자식 고아 |
| `_children.remove(...)` 만 하고 `orphanRemoval = false` | DB 에서 안 지워짐 |

**가드**: 부모 PK 컬럼은 `@JoinColumn` 단독 관리 + `insertable=false, updatable=false`. 자식의 `parentId` 는 읽기 전용.

---

### 3️⃣ 외부 라이브러리 / 프레임워크 누출 (Signature-level)

verify-architecture 가 *import* 차원만 본다면, verify-code 는 **시그니처 차원** 누출을 잡는다.

| 확인 | 위반 예 |
|---|---|
| `domain` Repository 가 `org.springframework.data.domain.{Page, Pageable, Sort}` 사용 | 도메인이 Spring Data 에 결합 |
| 도메인 모델이 `JsonNode`, `ObjectMapper`, `RedisTemplate`, `RestTemplate` 인자 | 도메인이 인프라 의존 |
| 도메인 VO 가 `@JsonProperty` / `@JsonInclude` / `@field:NotNull` 보유 | 표현 / 검증 어노테이션이 도메인에 |
| 도메인이 `org.springframework.web.server.ResponseStatusException` 사용 | HTTP 표현이 도메인에 |
| 도메인 시그니처가 `kotlinx.coroutines.flow.Flow` / `reactor.core.publisher.Mono` | 비동기 모델이 도메인에 (선택 — 의식적 결정 시 OK) |

**가드**: 도메인은 자체 VO 만 알고, 변환은 RepositoryImpl / Facade 에서.

확인:
```
Grep "^import org\.springframework\."  path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import com\.fasterxml\."        path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import io\.lettuce\.|^import redis\." path=apps/stay-api/src/main/kotlin/com/stayloop/domain
```

---

### 4️⃣ 컬렉션 / 페이지 결정성

`@OneToMany` 컬렉션과 페이징 응답이 **DB 재조회 후에도 같은 순서** 인가.

| 확인 | 위반 예 |
|---|---|
| 자식 컬렉션에 `displayOrder` 가 있는데 `@OrderBy` / `@OrderColumn` 누락 | 매번 다른 순서 |
| `findAll()` / `findByX()` 가 정렬 없이 응답 | 페이지마다 흔들림 |
| 페이지 응답의 `total` 을 `List.size` 로 잘못 사용 | 페이지 응답이 잘못됨 |
| `Set` / `HashMap` 으로 순서 의미 있는 컬렉션 표현 | 비결정 순회 |

**가드**: `@OneToMany` + `@OrderBy("col ASC")` 또는 메서드 시그니처가 정렬 인자 보유.

---

### 5️⃣ 캐시 / 역정규화 / 단일 진실 원천 (SSOT)

성능을 위해 도입한 캐시 컬럼(`mainImageUrl`, `wishCount`, `reviewAvg`) 이 **원본과 항상 동기** 인가.

| 확인 | 위반 예 |
|---|---|
| 캐시 컬럼만 갱신, 원본 컬렉션 플래그 미동기화 | `mainImageUrl = url` 만 → `is_main = TRUE` 가 0개/2개 |
| 원본은 갱신했는데 캐시 컬럼 누락 | 캐시 stale |
| 캐시 갱신이 **원본 검증 없이** 입력을 캐시에 저장 | 갤러리에 없는 URL 이 `mainImageUrl` 에 들어감 |

**가드**: 캐시 갱신 메서드는 (1) 원본 검증 → (2) 둘 동시 갱신 → (3) 검증 실패 시 둘 다 불변.

---

### 6️⃣ 입력 검증 / 도메인 불변식 보호

생성자 / `create()` / `from()` 에 **모든** 도메인 불변식이 검증되는가.

| 확인 | 위반 예 |
|---|---|
| `@Column(nullable = false)` 인데 도메인 init 검증 없음 | DB 가 막아주길 기다림 |
| 인공 PK 참조가 0/음수 허용 | `propertyId: Long` 0 통과 |
| 음수 / 0 / 빈 문자열 / 너무 긴 문자열 가드 누락 | `Money(-1)` / `Name("")` 통과 |
| 빈 컬렉션 허용 안 되는데 `emptyList()` 통과 | `BedConfig(emptyMap())` |
| 멱등 흐름에서 카운터 음수 진입 | `decrement()` 가 가드 없음 |
| 상태 전이가 `canTransitTo` 검증 없이 직접 set | `status = CANCELLED` 직접 대입 |
| Map / Set 컬렉션 입력의 키 / 값 nullable 검증 누락 | `Map<K, V>` 의 V 가 null 통과 |

**가드**: `init { require(...) }` 또는 `if (...) throw CoreException(ErrorType.X, "...")`. DB 제약은 *마지막* 방어선.

---

### 7️⃣ 멱등성 / 부수효과 일관성

같은 요청을 두 번 호출해도 도메인 상태가 일관되게 유지되는가.

| 확인 | 위반 예 |
|---|---|
| `wish` 멱등인데 카운트가 두 번 증가 | 같은 요청 2번 → wish_count +2 |
| `unwish` 가 미찜 상태에 호출되면 카운트 음수 | `wishCount` 가드 없음 |
| `cancel` 이 이미 취소된 상태에 호출되면 inventory 두 번 복원 | 상태 전이 검증 누락 |
| 외부 호출이 트랜잭션 중간에 일어나 롤백 시 부수효과만 남음 | TX 내 SMS 발송 |

**가드**: 멱등 진입점 = (1) 현재 상태 검사 → (2) 변화 없으면 noop → (3) 변화 있으면 한 번만.

---

### 8️⃣ 사일런트 디폴트 / 빈 값 변환

null / 빈 입력이 **조용히** 정상 값으로 변환되어 버그를 늦게 폭발시키는 패턴.

| 확인 | 위반 예 |
|---|---|
| `convertToDatabaseColumn(null)` → `"{}"` 빈 맵 JSON | 사일런트 디폴트 |
| `find...()` 결과 null 을 빈 객체로 변환 | "없음" 을 인지 못함 |
| `?: defaultValue` 가 비즈니스 의미 검토 없이 사용 | 잘못된 디폴트가 정답인 양 |
| `try { ... } catch { return emptyList() }` | 진짜 실패 무시 |
| `if (x.isNullOrBlank()) "" else x` 같은 정규화 없는 변환 | 의미 손실 |

**가드**: null / 빈 값이 정상 도메인 값이 아니면 즉시 예외.

---

### 9️⃣ 동시성 / 스레드 안전성

| 확인 | 위반 예 |
|---|---|
| 도메인 모델의 `var` 필드를 다중 스레드가 동시 변경 | 데이터 경합 |
| 정적 mutable 컬렉션 (`mutableListOf<>` companion) | 동시 변경 |
| `ThreadLocal` 누수 (요청 종료 시 clear 누락) | 스레드 풀에서 데이터 누출 |
| `lazy { }` 가 동기화 없이 stateful 객체 초기화 | 첫 호출 race |
| `@Async` 메서드가 트랜잭션 컨텍스트 전파 가정 | 트랜잭션 끊김 |
| 컬렉션 순회 중 변경 (`ConcurrentModificationException` 위험) | `for (x in list) list.remove(x)` |
| 단일 스레드 가정의 카운터를 production 으로 그대로 가져감 | `wishCount += 1` race |

**가드**: 본 라운드(2~3주차)는 단일 스레드 가정 — 다만 **4주차에서 락/원자 연산이 들어올 자리** 를 본 라운드 코드가 이미 막지 않게 한다 (예: 카운터 갱신을 모델 메서드로 캡슐화).

---

### 🔟 자원 관리 (Resource Lifecycle)

| 확인 | 위반 예 |
|---|---|
| `Closeable` / `AutoCloseable` 자원이 `use { }` / try-with-resources 없이 사용 | 누수 |
| 외부 클라이언트(HTTP, Redis, JDBC) 의 connection / pool 정리 누락 | 풀 고갈 |
| 파일 / Stream 처리에서 close 누락 | FD 누수 |
| `Flux` / `Mono` 의 dispose / cancel 처리 누락 | 메모리 누수 |
| `Schedulers` 직접 생성하고 종료 안 함 | 스레드 누수 |

**가드**: 자원은 항상 `use { }` 또는 framework 의 lifecycle 에 위임.

---

### 1️⃣1️⃣ 시간 / Clock / Timezone

| 확인 | 위반 예 |
|---|---|
| 도메인이 `LocalDateTime.now()` / `Instant.now()` 직접 호출 | 테스트에서 시간 고정 불가 |
| 시간 계산이 `Clock` 주입 없이 진행 | 비결정 테스트 |
| `LocalDateTime` 과 `ZonedDateTime` 의 무분별 혼용 | 시간대 사고 |
| `Date` (legacy java.util.Date) 사용 | 문자열 기반 시간대 사고 |
| KST/UTC 변환 없이 DB `TIMESTAMP` 와 도메인 `LocalDate` 매핑 | `04-erd.md §0` 위반 |

**가드**: 도메인은 `Clock` 을 인자로 받아 `LocalDateTime.now(clock)`. 1주차의 `config/ClockConfig.kt` 사용. DATE 변환 시 명시적으로 `ZoneId.of("Asia/Seoul")`.

---

### 1️⃣2️⃣ 예외 처리 / 에러 전략

| 확인 | 위반 예 |
|---|---|
| `catch (e: Exception) { ... }` / `catch (e: Throwable) { ... }` 의 광범위 catch | 진짜 실패 삼킴 |
| catch 블록에서 로그만 찍고 swallow | "No-op except log" |
| 도메인이 `RuntimeException` / `IllegalArgumentException` 을 직접 throw | `CoreException(ErrorType, ...)` 컨벤션 위반 |
| `e.printStackTrace()` 사용 | 표준 로거 미사용 |
| catch 후 새 예외 throw 시 `cause` 보존 안 함 | stack trace 단절 |
| `try { ... } catch { return null }` 형태 | 호출자가 실패 인지 못함 |

**가드**: 도메인은 `CoreException(ErrorType, message)`. infrastructure 예외 catch 시 `cause` 보존하고 의미 있는 ErrorType 으로 재포장.

---

### 1️⃣3️⃣ 매직 상수 / 가독성

| 확인 | 위반 예 |
|---|---|
| 매직 넘버 / 매직 스트링이 코드 본문에 흩어짐 | `if (length > 50)` / `"WIFI"` |
| `companion object const` 로 추출되지 않은 임계값 | `MAX_LENGTH = 50` 누락 |
| 메서드 시그니처에 `Boolean` 플래그 (`fun foo(force: Boolean)`) 가 의미 모호 | 호출 측 가독성 |
| 깊은 중첩 `if`/`when` (3레벨 이상) | 분기 의도 불명확 |
| 함수가 100라인 초과 / 인자 6개 초과 | 책임 분해 부족 |
| 의미 없는 변수명 (`a`, `tmp`, `data`, `list`) | 도메인 어휘 누락 |

**가드**: 임계값은 `companion object` 의 `private const`. Boolean 플래그 대신 sealed class / enum.

---

### 1️⃣4️⃣ 코드 중복 (DRY)

| 확인 | 위반 예 |
|---|---|
| 같은 검증 로직이 여러 VO 에 중복 (예: 여러 곳의 `if (value.isBlank()) throw ...`) | 가드 함수 추출 후보 |
| Converter 들이 동일 ObjectMapper 초기화 코드 반복 | 공용 베이스 / Bean 추출 |
| Facade 들이 같은 인가 검증 (loginId 일치) 반복 | `requireOwner(...)` 추출 |
| Repository 구현이 비슷한 변환 로직 반복 | 공용 매퍼 |

**가드**: 3회 이상 반복 = 추출 권장. 단, 무리한 추상화는 더 큰 비용 — 의도가 다른 중복은 그대로 두는 게 낫다.

---

### 1️⃣5️⃣ 가시성 / 캡슐화

| 확인 | 위반 예 |
|---|---|
| `var` 필드가 `public` 노출 (`var wishCount: Int = 0`) | 외부에서 직접 변경 가능 |
| 내부 변경 메서드(`internal fun`) 가 `public` | 의도치 않은 호출 가능 |
| 도메인 모델의 컬렉션이 mutable 노출 (`val items: MutableList<>`) | 외부에서 add/remove |
| `protected set` 누락 | JPA 가 set 하는 것과 외부가 set 하는 것 분리 못함 |
| `internal constructor` 없는 모델 — `new Model(...)` 직접 호출 가능 | `create()` 우회 |

**가드**: 도메인 모델은 `internal constructor` + `protected set` + `companion object create()`. 컬렉션은 backing field `_items` + `val items: List<...> get() = _items.toList()`.

---

### 1️⃣6️⃣ 트랜잭션 / 영속성 함정

| 확인 | 위반 예 |
|---|---|
| Facade 가 `@Transactional` 없이 여러 Repository 호출 | 부분 실패 시 일관성 깨짐 |
| 도메인 서비스에 `@Transactional` (Stayloop 컨벤션 위반) | TX 단일 진입 위반 |
| `@Transactional(readOnly = true)` 안에서 쓰기 | 런타임 실패 |
| `@Transactional` 메서드가 같은 클래스의 다른 메서드 호출 (self-invocation) | TX 적용 안됨 |
| Lazy 컬렉션을 트랜잭션 밖에서 접근 | LazyInitializationException |
| `flush()` / `clear()` 직접 호출 | 영속성 컨텍스트 의도 깨짐 |

**가드**: `@Transactional` = Facade 한 곳. Lazy 컬렉션은 Facade 내부에서만 접근.

---

### 1️⃣7️⃣ 성능 함정 (조기 경보)

성능 최적화는 4주차 영역이지만, **본 라운드 코드가 4주차에서 풀기 어려운 함정을 만들지 않게** 본다.

| 확인 | 위반 예 |
|---|---|
| `findAll()` 후 메모리 필터링 | 항상 페이징/Where |
| 루프 안에서 Repository 호출 (N+1) | `properties.forEach { repo.findX(it.id) }` |
| `@OneToMany(fetch = EAGER)` 무분별 사용 | 항상 join |
| 큰 텍스트(`@Column(columnDefinition = "TEXT")`) 항상 SELECT | projection 미적용 |
| 인덱스 없는 컬럼으로 빈번 검색 | 본 라운드 ERD 와 정합 확인 |
| 배열 / 컬렉션 변환을 매 호출마다 (`toList()` 반복) | 캐싱 후보 |

**가드**: 루프 안 Repository 호출 → `findAllByXIn(...)` 시그니처. EAGER 는 명시적 이유 없으면 LAZY.

---

### 1️⃣8️⃣ 보안 (Stayloop 컨텍스트)

| 확인 | 위반 예 |
|---|---|
| 시크릿 / API 키 / DB 비밀번호가 코드 / `application.yml` 에 평문 | 외부화 필요 |
| 사용자 입력을 SQL native query 에 문자열 결합 | SQL injection — `@Param` 사용 |
| 로그에 PII (전화번호 / 이메일 / 비밀번호) 그대로 출력 | 마스킹 필요 |
| `X-Loopers-LoginId` 헤더 검증 없이 신뢰 | 인가 누락 |
| 본인 자원 검증이 도메인 안 (도메인이 누가 호출했는지 알아서는 안됨) | Application Layer 책임 |
| Path Traversal — 사용자 입력을 파일 경로에 사용 | sanitize 누락 |
| OWASP Mass Assignment — `@RequestBody` 가 도메인 모델 직접 받음 | DTO 분리 필요 |

**가드**: 시크릿 = 환경변수. 본인 자원 검증 = Application Layer. SQL 은 `@Query` + `@Param`.

---

### 1️⃣9️⃣ 도메인 어휘 / Ubiquitous Language

| 확인 | 위반 예 |
|---|---|
| 코드의 식별자가 `01-requirements.md §1` 의 어휘와 어긋남 | `Hotel` vs `Property` 혼용 |
| ENUM 값이 비즈니스 어휘가 아닌 기술 약자 | `STATE_1`, `T2` |
| 같은 개념이 여러 이름 (`user` / `member` / `account`) | 혼동 |
| 영문/한글 혼용 (`getUserMok` 같은) | 일관성 |

**가드**: `01-requirements.md §1 도메인 용어` 표를 단일 진실 원천으로.

---

### 2️⃣0️⃣ 회귀 방지 / 테스트 시나리오 권고

각 항목에서 **위반이 발견되면**, 보완 코드와 함께 **회귀 방지 테스트** 도 권고한다.
verify-tests 게이트가 그 테스트를 강제 — 두 게이트는 한 쌍.

권고 예시:
- "갤러리에 없는 URL 으로 `replaceMainImage` 호출 시 BAD_REQUEST 거절 + 상태 불변" 테스트
- "신규 Property 에 자식 추가 후 저장/재조회 시 자식의 `property_id` 가 부모 id 와 동일" JPA round-trip 테스트
- "`displayOrder` 가 다른 이미지 2개 저장 후 재조회 시 ASC 순서" 테스트
- "`Converter.convertToDatabaseColumn(null)` 호출 시 INTERNAL_ERROR" 테스트
- "`RoomTypeModel.create(propertyId = 0)` 거절" 테스트
- "`@Transactional` self-invocation 검출" 통합 테스트
- "현재 시각 의존 로직의 `Clock` 고정 단위 테스트"

---

### 2️⃣1️⃣ 출력 포맷

```markdown
## verify-code 결과: {기능명}

### 컨텍스트
- 변경 파일: ...
- 신규 외부 라이브러리 / 호출: 있음/없음

### Null 일관성
- 위반: 0건 / N건 (위치 + 내용)

### 자식 Entity 식별자 동기화
- ...

### 외부 라이브러리 누출 (시그니처)
- ...

### 컬렉션 / 페이지 결정성
- ...

### 캐시 / 단일 진실 원천
- ...

### 입력 검증 / 불변식
- ...

### 멱등성 / 부수효과 일관성
- ...

### 사일런트 디폴트
- ...

### 동시성 / 스레드 안전성
- ...

### 자원 관리
- ...

### 시간 / Clock / Timezone
- ...

### 예외 처리 / 에러 전략
- ...

### 매직 상수 / 가독성
- ...

### 코드 중복 (DRY)
- ...

### 가시성 / 캡슐화
- ...

### 트랜잭션 / 영속성 함정
- ...

### 성능 함정 (조기 경보)
- ...

### 보안
- ...

### 도메인 어휘
- ...

### 회귀 방지 권고
- 1) ...
- 2) ...

### 게이트 결정
- ✅ PASS — verify-architecture 진행 가능
- ❌ FAIL — 다음 항목 보완 필요 (우선순위 P0/P1/P2):
  - P0) ...
  - P1) ...
  - P2) ...
```

---

### 2️⃣2️⃣ 점검 우선순위 (P0/P1/P2)

분량이 많기 때문에 모든 항목을 동등하게 다루지 않는다. 변경 단위와 무관하게 우선 검토:

- **P0 (반드시 차단)**: §1 Null 일관성 / §2 자식 Entity 식별자 / §3 외부 라이브러리 누출 / §6 입력 검증 / §16 트랜잭션 / §18 보안
- **P1 (강한 권고)**: §5 캐시 SSOT / §7 멱등성 / §9 동시성 / §11 시간 / §12 예외 / §15 가시성
- **P2 (권고)**: §4 결정성 / §8 사일런트 디폴트 / §10 자원 / §13 매직 상수 / §14 DRY / §17 성능 / §19 어휘

P0 위반 1건도 FAIL. P1 / P2 는 누적 정도와 영향 범위로 판단.

---

### 2️⃣3️⃣ 톤 & 원칙

- **코드를 직접 수정하지 않는다.** 위반을 적시하고 어떻게 고칠지는 개발자가 결정한다.
- 100% 순수성을 강요하지 않는다. 이탈은 **이유와 함께** 명시되어야 PASS.
- FAIL 시 **회귀 방지 테스트도 함께** 권고한다 — verify-tests 게이트가 받는다.
- "지적이 많아 보일 때" 는 우선순위로 압축한다 — P0 부터 해소되면 P1/P2 는 후속 PR.
- 한국어 응답을 기본으로 한다.
- 본 스킬이 FAIL 이면 verify-architecture 로 넘어가지 않는다 — 경계가 멀쩡해도 본문에 사일런트 사고가 있으면 의미 없다.
