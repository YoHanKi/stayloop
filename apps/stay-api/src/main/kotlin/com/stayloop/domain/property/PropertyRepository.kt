package com.stayloop.domain.property

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    /**
     * 도시 코드로 페이징 조회. 정렬은 `query.sort` 의 도메인 어휘 (`wishCount`, `rating`, `name` 등)를
     * RepositoryImpl 가 인프라 정렬 키로 매핑한다.
     */
    fun findByCity(city: String, query: PageQuery): PageResult<PropertyModel>

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
