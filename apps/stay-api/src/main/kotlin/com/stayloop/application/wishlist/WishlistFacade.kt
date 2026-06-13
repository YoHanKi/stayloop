package com.stayloop.application.wishlist

import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 찜 토글·조회 유스케이스. 토글 멱등은 여기서 — POST/DELETE 2회에도 행 1개, `wishCount` 는 1 만 증감한다(AC-6).
 *
 * wish 흐름은 `existsBy`(LoginId→users.id SELECT) 후 `save`(또 SELECT) 의 read-then-write 중복이 있다.
 * 도메인 boundary 가 LoginId 만 노출하는 결정을 지키기 위해 받아들이며, 단일 쿼리 통합과 wishCount 동시
 * 증감 정합성은 4주차 동시성·캐시 라운드의 영역이다.
 */
@Service
class WishlistFacade(
    private val wishlistRepository: WishlistRepository,
    private val propertyRepository: PropertyRepository,
    private val clock: Clock,
) {
    @Transactional
    fun wish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (!wishlistRepository.existsBy(loginId, propertyId)) {
            wishlistRepository.save(loginId, propertyId, LocalDateTime.now(clock))
            property.incrementWishCount()
            propertyRepository.save(property)
        }
        return WishlistToggleInfo(propertyId, wished = true, wishCount = property.wishCount)
    }

    @Transactional
    fun unwish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (wishlistRepository.existsBy(loginId, propertyId)) {
            wishlistRepository.deleteBy(loginId, propertyId)
            property.decrementWishCount()
            propertyRepository.save(property)
        }
        return WishlistToggleInfo(propertyId, wished = false, wishCount = property.wishCount)
    }

    @Transactional(readOnly = true)
    fun getMyWishes(
        requester: LoginId,
        target: LoginId,
        page: Int,
        size: Int,
    ): List<WishlistItemInfo> {
        if (requester != target) {
            throw CoreException(ErrorType.FORBIDDEN, "다른 사용자의 찜 목록은 조회할 수 없습니다.")
        }
        val wishes = wishlistRepository.findByUserId(target, page, size)
        val propertiesById = propertyRepository.findAllByIds(wishes.map { it.propertyId }).associateBy { it.id }
        return wishes.mapNotNull { wish ->
            val property = propertiesById[wish.propertyId] ?: return@mapNotNull null
            WishlistItemInfo(
                propertyId = property.id,
                name = property.name.value,
                city = property.address.city,
                mainImageUrl = property.mainImageUrl,
                wishedAt = wish.wishedAt.toString(),
            )
        }
    }
}
