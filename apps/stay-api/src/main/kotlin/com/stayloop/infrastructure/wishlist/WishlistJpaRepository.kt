package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Spring Data JPA 인터페이스. 도메인 `WishlistRepository` 와 분리되어 있고
 * `RepositoryImpl` 이 위임한다 (3-class 분리).
 *
 * `JpaRepository<WishlistModel, WishlistId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 *
 * **boundary 차이**: 도메인 인터페이스는 `LoginId` 를 받지만 본 인터페이스는 `users.id` (Long) 를 받는다 —
 * `LoginId ↔ users.id` 변환은 `WishlistRepositoryImpl` 의 책임 (`docs/plan/week2-3.md §⑥`).
 */
interface WishlistJpaRepository : JpaRepository<WishlistModel, WishlistId> {

    /**
     * `userId` (BIGINT FK) 기준 페이지 조회. 정렬은 `Pageable.sort` 로 외부에서 주입.
     */
    @Query("SELECT w FROM WishlistModel w WHERE w.userId = :userId")
    fun findByUserId(@Param("userId") userId: Long, pageable: Pageable): Page<WishlistModel>
}
