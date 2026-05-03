package com.stayloop.domain.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.value.LoginId

/**
 * `Wishlist` 의 도메인 Repository 인터페이스. (`docs/design/03-class-diagram.md §3`, `docs/plan/week2-3.md §⑥`)
 * 구현체는 `infrastructure/wishlist/WishlistRepositoryImpl` (Spring Data 위임).
 *
 * **boundary 는 `LoginId`** — Facade / 도메인 서비스가 `LoginId` 를 들고 다닐 수 있도록 인터페이스 시그니처에
 * `LoginId` 를 명시한다. `users.id` (BIGINT) 로의 변환은 Impl 이 `UserRepository.findByLoginId` 로 캡슐화 —
 * 도메인 호출부는 BIGINT 를 모른다.
 *
 * 누락 사용자 정책: `findByLoginId` 가 null 을 돌려주면
 * - `existsBy` / `findByUserId` 는 빈 결과 (조회는 silent empty — "찜 없음" 과 의미적으로 동일)
 * - `save` / `deleteBy` 는 `NOT_FOUND` 로 거절 (쓰기는 사용자 부재가 명시적 오류)
 */
interface WishlistRepository {
    /**
     * 자연키 `(userId, propertyId)` 의 찜 행 존재 여부.
     * 사용자 부재 시 false (저장된 행이 없는 것과 같다).
     */
    fun existsBy(userId: LoginId, propertyId: Long): Boolean

    /**
     * 새 찜 행을 저장한다 — 같은 자연키 행이 이미 있으면 upsert.
     * 사용자 부재 시 `NOT_FOUND` (boundary 에서 LoginId 를 받았으나 매핑되는 사용자가 없는 사고 케이스).
     *
     * 멱등 처리(이미 찜된 경우 noop)는 호출자(`WishlistFacade`) 책임 — Repository 는 단순 쓰기만 수행.
     */
    fun save(userId: LoginId, propertyId: Long, wishedAt: java.time.LocalDateTime): WishlistModel

    /**
     * 자연키 `(userId, propertyId)` 의 찜 행을 삭제한다. 행이 없으면 noop.
     * 사용자 부재 시도 noop (삭제할 행이 없음).
     */
    fun deleteBy(userId: LoginId, propertyId: Long)

    /**
     * 사용자의 찜 목록을 페이지 단위로 조회한다 — `wishedAt` 내림차순(최신순) 기본.
     * 사용자 부재 시 빈 리스트.
     */
    fun findByUserId(userId: LoginId, page: PageQuery): List<WishlistModel>
}
