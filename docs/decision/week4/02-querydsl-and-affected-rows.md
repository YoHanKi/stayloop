# 커스텀 쿼리는 QueryDSL, 영향 행 수 해석은 도메인 서비스

week4 chunk 1 리팩토링. [01-inventory-concurrency-strategy.md](01-inventory-concurrency-strategy.md) 에서 조건부 원자 UPDATE 를 채택한 뒤, 그 쿼리를 어떻게 작성하고 "영향 행 수 0 → 품절" 판단을 어느 계층에 둘지를 정한다.

## 1. 맥락 / 문제

채택한 조건부 차감은 처음에 두 가지를 함께 갖고 있었다.

1. 쿼리를 `@Modifying @Query` JPQL 문자열로 작성.
2. 영속성 어댑터(`ConditionalUpdateRoomInventoryReserver`)가 영향 행 수를 직접 검사해 `affected != size` 면 `CONFLICT` 예외를 던짐.

둘 다 결이 안 맞는다. (1) 문자열 JPQL 은 컴파일 타임 검증·리팩토링 안전성이 없다. (2) "0 행 = 품절" 은 비즈니스 규칙인데 그것을 영속성 어댑터가 알고 예외까지 만든다 — 쿼리 계층이 업무 판단을 침범한다.

## 2. 선택지와 고민

**쿼리 작성 방식**
- `@Query`(JPQL 문자열): 간단하지만 타입 안전성·동적 조건 약함, 난해해질수록 문자열이 깨지기 쉽다.
- QueryDSL: 타입 안전, 컴파일 검증, 동적 조건·재사용 용이. 프로젝트에 이미 `JPAQueryFactory` 빈이 있다.

**영향 행 수 해석 위치**
- 영속성 어댑터가 검사·예외(초기 구현): 호출부는 간단하나 업무 규칙이 어댑터에 샌다.
- Repository 는 영향 행 수(사실)만 반환, 도메인 서비스가 해석: 어댑터는 dumb, 규칙은 도메인. 반환 타입에 `Int`(행 수)가 노출되는 약한 누출은 감수.

업계 관례를 확인했다. 리포지토리는 단순 값(count·null)을 반환하고 비즈니스 예외 결정은 도메인/애플리케이션 서비스가 맡는 것이 클린 아키텍처 통설이다(아래 출처). 재고 차감에서 affected rows 0 → 재고부족 처리는 흔한 패턴이지만, 그 해석은 서비스 레이어의 몫이다.

## 3. 코드 예시

영속성(어댑터) — QueryDSL, 사실만 반환:

```kotlin
override fun deductIfAvailable(roomTypeId: Long, dates: List<LocalDate>): Int =
    queryFactory.update(inventory)
        .set(inventory.reservedRooms, inventory.reservedRooms.add(1))
        .where(
            inventory.roomTypeId.eq(roomTypeId),
            inventory.date.`in`(dates),
            inventory.reservedRooms.add(1).loe(inventory.totalRooms),
        )
        .execute().toInt() // 차감된 일자 수(fact)
```

도메인 서비스 — 사실을 업무 규칙으로 해석:

```kotlin
fun reserve(roomTypeId: Long, dates: List<LocalDate>) {
    val target = dates.distinct()
    val deducted = inventoryRepository.deductIfAvailable(roomTypeId, target)
    if (deducted != target.size) {
        throw CoreException(ErrorType.CONFLICT, "예약 가능한 객실이 없는 날짜가 있습니다.")
    }
}
```

## 4. 실제 테스트 환경

- 정합성: 실 MySQL 8.0(Testcontainers) `ConcurrentInventoryReserveTest` — 운영 `DailyRoomInventoryRepositoryImpl`(QueryDSL) + `DailyRoomInventoryService` 를 그대로 조립해 동시 차감/복원을 검증. 더블부킹·부분 차감·음수 부재 + 카운터 정합.
- 단위/통합: `ReservationFacadeTest`(매진/부재 CONFLICT, 부분 차감 0), `ReservationServiceTest`. InMemory 더블은 트랜잭션이 없으므로 전 일자 가용 시에만 차감해 운영(차감+롤백)과 같은 all-or-nothing 관측 결과를 준다.

## 5. 판단 기준

타입 안전성·계층 책임 정합(영속성=사실, 도메인=판단)·테스트 가능성. 성능은 동일(같은 SQL 로 컴파일됨).

## 6. 결과

- 5개 리포지토리(inventory/rate/wishlist/reservation/property)의 `@Query`/`@Modifying` 을 전부 QueryDSL 로 이관, 각 `JpaRepository` 는 `JpaRepository<T, ID>`(CRUD)만 상속.
- 재고 차감/복원은 Repository 가 영향 행 수만 반환, `DailyRoomInventoryService` 가 "수가 요청과 다르면 CONFLICT/BAD_REQUEST" 를 명시적으로 판단. 별도 reserver 포트·어댑터·설정 스위치 제거.
- 전체 테스트 통과(동시성 3건 포함).

## 7. 트레이드오프

- QueryDSL 은 kapt Q클래스 생성 의존(빌드에 한 단계). 대신 타입 안전·동적 쿼리 이득.
- Repository 가 `Int`(행 수)를 반환 — 약한 영속성 개념 누출. 그러나 "차감된 일자 수" 는 도메인 의미가 있고, 업무 판단을 어댑터에 두는 것보다 훨씬 낫다.
- 도메인 서비스(`DailyRoomInventoryService`)가 한 겹 늘었다. 단순 위임이 아니라 all-or-nothing 규칙을 소유하므로 정당.

## 8. 결론

**커스텀 쿼리는 QueryDSL, JpaRepository 는 CRUD 전용. 조건부 갱신의 영향 행 수는 Repository 가 사실로 반환하고, 그 해석(품절 판단)은 도메인 서비스가 명시적으로 한다.** 이 규칙은 CLAUDE.md "쿼리 작성" 에 박제했다 — 앞으로 조금이라도 난해한 쿼리는 QueryDSL 로, 업무 판단은 쿼리 계층 밖에서.

## 9. 후속 / 미해결

- lock-wait timeout·deadlock victim 의 실패 종류별 재시도는 chunk 3. 현재 Facade 가 `PessimisticLockingFailureException` 을 CONFLICT 로 흡수만 한다(Spring DAO 예외는 도메인 서비스를 거쳐 Facade 로 전파).

## 출처

- [Should repositories throw exceptions? — Mina Sami](https://minasami.com/2020/09/14/should-repositories-throw-exceptions.html)
- [Error handling and strategies — Roman Dykyi](https://medium.com/@dykyi.roman/error-handling-and-strategies-a55b5a285b6b)
- [쇼핑몰 재고관리 동시성 문제(갱신손실·데드락)](https://velog.io/@dangddoong/shopping-mall-Inventory-management-concurrency-issues-analysis-deadlock-and-lost-update)
