package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 조회 쿼리는 QueryDSL(RepositoryImpl)로 둔다. */
interface WishlistJpaRepository : JpaRepository<WishlistModel, WishlistId>
