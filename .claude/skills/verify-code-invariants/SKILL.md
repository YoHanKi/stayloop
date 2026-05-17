---
name: verify-code-invariants
description: |
  verify-code orchestrator 의 **Phase 1 sub-skill** — 도메인 본문의 정적 결함.
  null 일관성 / 외부 라이브러리 시그니처 누출 / 캐시-원본 SSOT / 입력 검증 + 컬럼-가드 정합 / 매직 상수 / 가시성 / PK 중복 인덱스.
user-invocable: true
---

# Phase 1 — Invariants

## Null 일관성 (3-way alignment)

도메인 타입 / DB 스키마 / 컨버터 시그니처 *세 곳 모두* null 정책이 일치해야 한다. DB 컬럼 `nullable = false` 인데 컨버터가 `attribute: T?` 로 null 통과시키거나, 도메인 타입 non-null 인데 `convertToEntityAttribute` 가 `T?` 반환하면 지적한다. 같은 코드베이스의 여러 컨버터가 *서로 다른 null 정책* 을 쓰면 일관성 위반이다 — 한 번에 grep 으로 확인한다.

NOT NULL 컬럼 컨버터는 null 입력 즉시 `CoreException(INTERNAL_ERROR)`. NULL 허용이면 도메인 타입도 `T?` 로 일관 유지.

## 외부 라이브러리 / 프레임워크 누출 (signature-level)

verify-architecture 가 *import* 차원을 본다면 본 phase 는 **시그니처 차원** 을 본다.

- `domain` Repository 시그니처에 `org.springframework.data.domain.{Page, Pageable, Sort}` 등장 → 도메인이 Spring Data 에 결합.
- 도메인 모델이 `JsonNode`, `ObjectMapper`, `RedisTemplate`, `RestTemplate` 등 인프라 타입을 인자로 받음.
- 도메인 VO 가 `@JsonProperty` / `@JsonInclude` / `@field:NotNull` 등 표현·검증 어노테이션 보유.
- 도메인이 `org.springframework.web.server.ResponseStatusException` 사용 — HTTP 표현이 도메인에 누출.
- 도메인 시그니처에 `Flow` / `Mono` 등 비동기 모델이 *의식적 결정 없이* 등장.

도메인은 자체 VO 만 알고, 인프라/외부 타입 변환은 RepositoryImpl 또는 Facade 에서 끝낸다.

## 캐시 / 역정규화 / 단일 진실 원천 (SSOT)

성능을 위해 도입한 캐시 컬럼(`mainImageUrl`, `wishCount`, `reviewAvg`) 이 *원본과 항상 동기* 인지 본다. 캐시 컬럼만 갱신하고 원본 컬렉션 플래그를 미동기화하거나 (`is_main = TRUE` 가 0개/2개), 원본은 갱신했는데 캐시 컬럼을 누락하거나 (캐시 stale), 캐시 갱신이 *원본 검증 없이* 입력을 캐시에 저장하면 (갤러리에 없는 URL 이 `mainImageUrl` 에 들어감) 지적한다.

특히 **공개 팩토리 `create()` 가 캐시 컬럼을 매개변수로 받으면** 메서드 SSOT 가드를 무력화하는 *생성 시점 우회 경로* 다. 캐시는 *상태 변경 메서드* 를 통해서만 갱신하고, `internal constructor` (JPA hydration 용) 는 매개변수를 유지하되 외부 진입점 (`create()`) 과 분리한다.

## 입력 검증 / 도메인 불변식 보호

생성자 / `create()` / `from()` 에 *모든* 도메인 불변식이 검증되는지 본다. DB 제약은 *마지막* 방어선 — `BAD_REQUEST` 가 아니라 500 으로 응답되면 사용자 경험 깨짐.

- `@Column(nullable = false)` 인데 도메인 init 검증 없음.
- **`@Column(length = N)` 인데 init length 가드 없음** — 두 N 은 동일 `companion const val X_MAX_LENGTH` 로 묶여야 한다. 한 쪽만 변경 시 어긋남.
- 캐시 컬럼(역정규화)에 length 가드 누락 — 원본만 가드하고 캐시 setter 를 우회.
- 인공 PK 참조가 0/음수 허용 (`propertyId: Long` 0 통과).
- 음수 / 0 / 빈 문자열 / 너무 긴 문자열 가드 누락 — `Money(-1)` / `Name("")` 통과.
- **비즈니스 의미 0 가드 누락** — "음수 거절" 만 하고 0 통과. 0이 *의미 없는 값* (할인 금액 / 가격 / 객실 수) 이면 `> 0`. *적용 시점에 항상 실패할 값을 발급 시점에 통과* 시키면 회귀 사각지대 (PR9 Copilot #1).
- 상태 변경 → 검증 순서가 뒤집힘 — 변경 먼저 하고 검증이 뒤에 오면 예외 시 부분 변경이 영속화. Strong Exception Safety.
- 외부 자원 mutation *이전* 에 cheap input guard 가 발동하는지 — Facade TX 가 없거나 호출자가 예외를 잡으면 부분 변경 잔존.
- 외부 주입 컬렉션 ↔ Aggregate 정합 가드 부재 — `cancel(reservation, inventories)` 에서 inventories 의 roomTypeId / dates 가 reservation 과 일치하는지 mutate 이전 검증.
- **JPA 컬럼 중복 매핑** — `@Embedded VO` 와 외부 entity 가 같은 `@Column(name = "X")` 매핑 시 Hibernate bootstrap 폭발. 단위 테스트는 통과하지만 풀 컨텍스트 / `@DataJpaTest` 시점에 터짐 — `@Column(name=...)` 횡단 grep 으로 사전 감지.

점검: `Grep "@Column.*length\s*=\s*\d+"` 로 모든 length 컬럼 추출 후 같은 파일 `length >` 가드 존재 대조. `Grep "@Column\(name\s*=\s*\""` 로 같은 entity 내 동일 컬럼명 두 곳 등장 횡단.

## 매직 상수 / 가독성

매직 넘버·스트링이 코드 본문에 흩어지면 `companion object` 의 `private const val` 로 추출 권한다. `Boolean` 플래그 인자는 sealed class / enum 으로 분해. 깊은 중첩 `if`/`when` (3레벨 이상) 은 분기 의도 불명확. 함수가 100라인 초과 / 인자 6개 초과면 책임 분해 부족 신호.

## 가시성 / 캡슐화

도메인 모델은 `internal constructor` + `protected set` + `companion object create()` 패턴을 따른다. `var` 필드가 `public` 노출되어 외부가 직접 변경 가능하거나, 컬렉션이 `MutableList<>` 로 노출되거나, `protected set` 누락으로 JPA set 과 외부 set 이 분리 안 되면 지적한다. 컬렉션은 backing field `_items` + `val items: List<...> get() = _items.toList()` 로 노출.

## PK 중복 인덱스 / Eager fetch

`@IdClass` / `@EmbeddedId` 의 PK 컬럼·순서와 동일한 `@Table(indexes = ...)` 보조 인덱스가 명시되면 — PK 자체가 같은 인덱스를 제공하므로 중복 인덱스가 쓰기 amplification + 스토리지 비용으로 누적. 단일 `@Id` 와 동일한 보조 인덱스도 동일. *다른* 접근 패턴 (자식 entity 의 `(parent_id, display_order)` / 비-PK 단독) 일 때만 명시한다. `@OneToMany(fetch = EAGER)` 는 명시적 이유 없으면 LAZY 권장.

`Grep "indexes\s*=" path=apps/stay-api/src/main/kotlin` → `@IdClass` / `@EmbeddedId` / `@Id` 컬럼과 columnList 비교.
