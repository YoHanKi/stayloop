package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WishlistJpaRepository : JpaRepository<WishlistModel, WishlistId> {
    @Query("SELECT w FROM WishlistModel w WHERE w.userId = :userId ORDER BY w.wishedAt DESC")
    fun findByUserId(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<WishlistModel>
}
