package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface WishlistJpaRepository : JpaRepository<WishlistModel, WishlistId> {
    /**
     * 자연 키 `(user_id, property_id)` 가 없을 때만 삽입하고 영향 행 수(1=새로 추가, 0=이미 존재)를 돌려준다.
     * "추가됐는지" 를 경합 없이 원자적으로 판정하기 위한 네이티브 INSERT IGNORE — QueryDSL/JPA 는 조건부 INSERT 를
     * 표현하지 못하고, assigned-id 엔티티의 `save` 는 merge(upsert)라 신규 여부를 알 수 없어 이 경로만 네이티브로 둔다.
     */
    @Modifying
    @Query(
        value = "INSERT IGNORE INTO wishlists (user_id, property_id, wished_at) VALUES (:userId, :propertyId, :wishedAt)",
        nativeQuery = true,
    )
    fun insertIgnore(
        @Param("userId") userId: Long,
        @Param("propertyId") propertyId: Long,
        @Param("wishedAt") wishedAt: LocalDateTime,
    ): Int
}
