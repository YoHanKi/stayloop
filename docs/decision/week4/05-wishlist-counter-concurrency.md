# 찜 수 비정규화 카운터 — 동시 증감 정합

week4 chunk 4. 관련 계획: `docs/plan/week4-b.md` §2·§5-4.

## 1. 맥락 / 문제

검색 정렬용 비정규화 카운터 `Property.wishCount` 는 실제 찜 행 수와 일치해야 한다. 기존 `WishlistFacade` 는 `existsBy`(racy) 후 엔티티를 읽어 `wishCount += 1` 하고 저장하는 read-modify-write라, 동시 요청에서 ① 카운터 Lost Update, ② 동시 중복 찜이 둘 다 "신규"로 판정돼 +2 되는 드리프트가 열려 있었다.

## 2. 선택지와 고민

**카운터 갱신**: 엔티티 변이(read-modify-write) → Lost Update. 조건부 원자 UPDATE(`SET wish_count = wish_count + 1`) → 직렬화·정합. chunk 1·2 와 동형으로 조건부 원자 UPDATE 채택(음수는 `WHERE wish_count > 0`).

**드리프트 방지(핵심)**: 카운터를 "찜이 실제로 추가됐을 때만" +1 해야 동시 중복 찜에도 행 수와 일치한다. 그러려면 "추가됐는지" 를 경합 없이 알아야 한다. 후보 ① `existsBy` 후 insert — 두 동시 요청이 둘 다 "신규" 판정(race). ② assigned-id 엔티티 `save` — merge(upsert)라 신규 여부를 못 줌. ③ **네이티브 `INSERT IGNORE`** — 자연 키 충돌 시 0행, 신규 시 1행을 원자로 돌려줌. ③ 채택. 제거는 QueryDSL `DELETE` 의 영향 행 수로 "실제 제거" 를 판정.

→ Repository 가 **추가/제거의 사실(Boolean)** 을 돌려주고, Facade 가 그 사실이 참일 때만 카운터를 증감한다(영속성=사실, 앱=판단; chunk 1~3 일관).

**응답 수치**: 벌크 UPDATE 는 영속성 컨텍스트(L1)를 우회하므로 로드된 엔티티의 `wishCount` 는 낡는다. 응답엔 갱신 직후 스칼라 조회(`findWishCount`)로 신선한 값을 싣는다.

## 3. 코드 예시

```kotlin
// WishlistJpaRepository — 네이티브 INSERT IGNORE (조건부 INSERT 는 QueryDSL/JPA 미지원, assigned-id save 는 merge)
@Modifying
@Query(value = "INSERT IGNORE INTO wishlists (user_id, property_id, wished_at) VALUES (:userId, :propertyId, :wishedAt)", nativeQuery = true)
fun insertIgnore(userId: Long, propertyId: Long, wishedAt: LocalDateTime): Int  // 1=신규, 0=이미 존재

// WishlistFacade — 사실이 참일 때만 카운터 원자 증감
if (wishlistRepository.add(loginId, propertyId, now)) {
    propertyRepository.incrementWishCount(propertyId)   // QueryDSL: SET wish_count = wish_count + 1
}
return WishlistToggleInfo(propertyId, wished = true, wishCount = propertyRepository.findWishCount(propertyId) ?: 0)
```

## 4. 실제 테스트 환경

- 정합성: 실 MySQL 8.0(Testcontainers) `ConcurrentWishlistTest` — 운영 `WishlistFacade` 빈 그대로.
  - 같은 사용자 20 동시 찜 → 행 1·wishCount 1(드리프트 없음).
  - 서로 다른 30 사용자 동시 찜 → wishCount 30 = 행 수.
  - 같은 사용자 찜 20 + 취소 20 동시 → wishCount = 실제 행 수(0 또는 1).
- 단위/통합: `WishlistFacadeTest`(토글 멱등), `InMemoryWishlistRepositoryTest`(add/remove 사실 반환). InMemory 더블은 `INSERT IGNORE` 를 Set 멤버십으로, 카운터를 `PropertyModel.incrementWishCount/decrementWishCount` 로 흉내낸다(reserveOne 패턴, 운영 SQL 과 동치).

## 5. 판단 기준

카운터 = 행 수 정합(드리프트 0), 조건부 원자 갱신(chunk 1~3 일관), 영속성=사실/앱=판단, 같은 트랜잭션.

## 6. 결과

- `Property.wishCount` 갱신을 조건부 원자 UPDATE 로 전환, 찜 row 추가/제거를 `INSERT IGNORE`/`DELETE` 영향 행 수로 판정해 사실이 참일 때만 카운터 증감. 동시 중복/혼합 토글에도 카운터 = 행 수.

## 7. 트레이드오프

- `INSERT IGNORE` 네이티브 SQL 은 CLAUDE.md "쿼리=QueryDSL" 규칙의 예외다. QueryDSL/JPA 가 조건부 INSERT(자연 키 충돌 무시 + 신규 여부 반환)를 표현하지 못하고, assigned-id `save` 는 merge라 신규 판정이 불가해 이 한 경로만 네이티브로 둔다(MySQL 전용). 위반이 아니라 표현력 한계의 정당한 예외로 박제.
- 응답에 신선한 수치를 싣기 위해 갱신 직후 스칼라 조회 1회 추가(벌크 UPDATE 의 L1 캐시 우회 보정).
- `PropertyModel.incrementWishCount/decrementWishCount` 는 운영 경로에선 안 쓰지만 InMemory 더블·모델 테스트가 도메인 전이를 표현하는 데 쓴다(reserveOne/releaseOne 과 같은 패턴) — 제거하지 않는다.

## 8. 결론

**찜 row 추가/제거의 사실(`INSERT IGNORE`/`DELETE` 영향 행 수)을 Repository 가 반환하고, 참일 때만 `wishCount` 를 조건부 원자 UPDATE 로 증감(같은 트랜잭션).** 응답 수치는 갱신 직후 스칼라 재조회. 카운터 = 실제 행 수가 동시성에서 보장된다.

되돌릴 트리거: 멀티 DB·샤딩으로 행 수와 카운터가 물리적으로 갈리면 재집계 배치/이벤트 기반 동기화로 전환.

## 9. 후속 / 미해결

- 카운터 정합 점검 배치(행 수 합 = 카운터)는 운영 관찰 도구로 별도(04-a §7).
- 찜 토글의 부하 실측(k6)은 필요 시 별도(예약 핫스팟과 독립).
