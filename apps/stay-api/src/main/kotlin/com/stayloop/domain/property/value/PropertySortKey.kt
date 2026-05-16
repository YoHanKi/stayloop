package com.stayloop.domain.property.value

/**
 * Property 검색 정렬 키. (`docs/plan/week5.md` D-6 — week5 PR1 에서 4종 모두 활성화)
 *
 * 본 enum 은 *도메인 어휘* 다 — application 의 검색 입력 (criteria) 과 infrastructure 의 SQL `ORDER BY`
 * 분기가 *공통 어휘* 를 공유한다. application 에 두면 infrastructure 가 application 을 의존해야 해서
 * 의존 방향 위반 (`docs/plan/week5-b.md` Loop 5 ↔ `verify-architecture` §계층 의존).
 *
 * **정렬 시맨틱:**
 * - [RECOMMENDED] — 기본 추천 (현재는 id ASC, 향후 가중치 합성 가능)
 * - [PRICE_ASC] — `daily_room_rates.price_per_night` 의 *Property 단위 최저가* 오름차순.
 *   다른 sort 와 달리 properties 의 단일 컬럼이 아니라 *기간 ↔ 객실 JOIN 후 MIN()* 가 필요.
 *   `documents/feature/property-search-perf-index/comparison.md` D-1 + week5.md PR1 의 K 의 overfetch 정합.
 * - [RATING_DESC] — `properties.rating` 내림차순 (idx_properties_city_rating prefix scan)
 * - [WISHES_DESC] — `properties.wish_count` 내림차순 (idx_properties_city_wish_count prefix scan)
 */
enum class PropertySortKey {
    RECOMMENDED,
    PRICE_ASC,
    RATING_DESC,
    WISHES_DESC,
}
