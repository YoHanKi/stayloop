package com.stayloop.domain.property

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.reservation.value.StayPeriod

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    /**
     * 도시 + 기간 + 정렬 키 기준 Property 페이지 조회. (week5 PR1 D-6 4종 활성화)
     *
     * **`sortKey` 별 SQL 의미론** (`docs/plan/week5.md` D-1 + D-6):
     * - [PropertySortKey.RECOMMENDED] — `properties` 의 `id ASC` (기본). `idx_properties_city` prefix scan.
     * - [PropertySortKey.WISHES_DESC] — `properties.wish_count DESC`. V010 의 `idx_properties_city_wish_count`
     *   prefix scan (filesort 없음).
     * - [PropertySortKey.RATING_DESC] — `properties.rating DESC`. V010 의 `idx_properties_city_rating` 동일.
     * - [PropertySortKey.PRICE_ASC] — *`daily_room_rates` JOIN GROUP BY MIN*. period 의 일자 범위 안에서
     *   각 Property 의 *RoomType × date 의 최저 per-night price* 로 정렬. *K = page.size × 3 candidate
     *   overfetch* — Facade 가 후속 가용성 N+1 필터 후 page.size 만큼 take (week5-b.md L3 / decompose-decision
     *   Q1~Q5 박제). 본 sort 만 PageResult.content 가 page.size 보다 클 수 있다 (최대 size×3).
     *
     * **`period` 사용** — PRICE_ASC 만 사용. 다른 sort 는 무시 (호출자 정합성을 위해 항상 전달).
     *
     * **`page.total`** — 모든 sort 에서 *city 매칭 row 수* (가용성 제외 X). AC-1 정합 — PRICE_ASC 의 후속
     * 가용성 필터는 Facade 가 응답값에서 처리하지만, total 의 의미는 *도시 매칭 count* 로 유지 (다른 sort 와 일관).
     */
    fun search(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        page: PageQuery,
    ): PageResult<PropertyModel>

    /**
     * Property 검색 *projection* — N+1 제거 + 가용성 / 최저가 / 가용 RoomType 수 집계 (week5 PR3 D-3).
     *
     * **반환**: city 매칭 + *가용 RoomType ≥ 1* Property 만 — 가용 0 Property 는 결과 제외 (AC-2).
     * `PageResult.total` 은 *city 매칭 전체 row 수* 유지 (`search` 의 total 의미와 정합 — 가용성 제외 X).
     *
     * **가용 조건**:
     * - `room_types.max_guests >= guestCount`
     * - 기간 내 *모든 일자* `daily_room_rates` 행 존재
     * - 기간 내 *모든 일자* `daily_room_inventories.total_rooms > reserved_rooms` (available > 0)
     *
     * **`sortKey` 별 ORDER BY** (V010 인덱스 + JOIN 정합):
     * - RECOMMENDED — `p.id ASC`
     * - WISHES_DESC — `p.wish_count DESC, p.id ASC`
     * - RATING_DESC — `p.rating DESC, p.id ASC`
     * - PRICE_ASC — `lowest_total ASC, p.id ASC` (Step 2 의 *실제 최저 합산가* 기준)
     *
     * **N+1 제거 효과**: 기존 `search + Facade.buildSearchInfoOrNull` 의 1 + N + N×M SQL 흐름 →
     * 본 메서드 4 SQL (total + candidates + rt aggregation + property fetch). PR1 의 *N+1 잔량 부담*
     * (k6 시나리오 A RPS 100 미달 / PR2 sort=WISHES_DESC SLA 미달 333ms) 의 직접 해소.
     */
    fun searchInfos(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        guestCount: Int,
        page: PageQuery,
    ): PageResult<PropertySearchRow>

    fun findAllByIds(ids: Collection<Long>): List<PropertyModel>

    fun deleteById(id: Long)

    /**
     * `wishCount` 를 *atomic* 으로 1 증가. (`docs/plan/week4.md` ③ Phase C-1, decision.md D-1 #4)
     *
     * **본질**: 찜 카운터는 *집계 / 순서 무관* 자원. read-modify-write (`findById → incrementWishCount → save`)
     * 흐름은 핫스팟 (인기 숙소) 에서 lost update race 를 만든다. SQL 1줄 (`UPDATE ... SET wish_count = wish_count
     * + 1 WHERE id = ?`) 로 *DB-side atomic* 갱신 — 락 비용 0, race window 0.
     *
     * **반환**: 영향 받은 행 수 (0 = 미존재 propertyId, 1 = 정상 증가). 미존재 시 0 반환은 *호출자 책임* —
     * Facade 가 호출 전 `findById` 로 NOT_FOUND 검증을 마쳐야 한다 (`WishlistFacade.wish` 흐름).
     *
     * **운영 ↔ 도메인 메서드의 관계**: 운영 흐름은 본 atomic 메서드를 우회하지만, 도메인 메서드
     * `Property.incrementWishCount()` 는 *마지막 방어선* 으로 유지 — 단위 테스트가 도메인 가드를 검증하고,
     * 운영 코드가 atomic 으로 우회해도 *영속성 단위* (JPA hydration 직후 호출되는 도메인 메서드) 의 가드는
     * 그대로 살아있다 (verify-code §19-B 문서 ↔ 가드 정합).
     *
     * **`@Version` 미보유 아키텍처와 정합** — Property 는 낙관적 락 컬럼이 없으므로 native UPDATE 가
     * silent stale 을 만들 수 없다 (verify-code R6 비해당). 미래 `@Version` 추가 시점에 본 메서드의 SQL 도
     * `version = version + 1` 명시 강제 (Phase 0 E-7 / Round 1 E-7-r1 박제).
     */
    fun atomicIncrementWishCount(propertyId: Long): Int

    /**
     * `wishCount` 를 *atomic* 으로 1 감소. **음수 진입 SQL 차단** — `WHERE wish_count > 0` 가드 (decision.md D-1 #4).
     *
     * **반환**: 영향 받은 행 수 (0 = 미존재 또는 이미 0, 1 = 정상 감소). 0 반환은 *멱등 noop* 의미 —
     * 미찜 상태에 unwish 가 잘못 호출되어도 DB 가 영구히 어긋나지 않는다.
     *
     * 도메인 메서드 `Property.decrementWishCount()` 는 마지막 방어선 (`wishCount <= 0` 시 `CONFLICT` throw) 으로
     * 유지 — KDoc 박제 의도는 `atomicIncrementWishCount` 와 동일.
     */
    fun atomicDecrementWishCount(propertyId: Long): Int
}
