package com.stayloop.domain.wishlist

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 찜. `(userId, propertyId)` 자연 키라 중복 찜이 식별자 수준에서 막힌다(03 §9). 검색용 집계값은
 * [com.stayloop.domain.property.PropertyModel.wishCount] 비정규화 캐시가 따로 들고, 그 동시성
 * 정확성은 4주차 영역의 알려진 공백이다.
 */
@Entity
@Table(name = "wishlists")
@IdClass(WishlistId::class)
class WishlistModel(
    userId: Long,
    propertyId: Long,
    wishedAt: LocalDateTime,
) {
    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    @Id
    @Column(name = "property_id", nullable = false)
    val propertyId: Long = propertyId

    @Column(name = "wished_at", nullable = false)
    val wishedAt: LocalDateTime = wishedAt

    init {
        if (userId <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "사용자 ID 는 양수여야 합니다.")
        }
        if (propertyId <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "숙소 ID 는 양수여야 합니다.")
        }
    }
}
