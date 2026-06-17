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
 * 동시 증감 정합(04-b §5-4): 찜 row 추가/제거의 **사실**(새로 추가/실제 제거)을 Repository 가 돌려주고,
 * 그 사실이 참일 때만 비정규화 카운터를 **조건부 원자 UPDATE** 로 증감한다. 찜 row 변경과 카운터 갱신을 같은
 * 트랜잭션에 둬, 동시 중복 요청에도 `wishCount` 가 실제 행 수와 일치한다. 응답 수치는 갱신 직후 신선하게 다시 읽는다.
 */
@Service
class WishlistFacade(
    private val wishlistRepository: WishlistRepository,
    private val propertyRepository: PropertyRepository,
    private val clock: Clock,
) {
    @Transactional
    fun wish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        requireProperty(propertyId)
        if (wishlistRepository.add(loginId, propertyId, LocalDateTime.now(clock))) {
            propertyRepository.incrementWishCount(propertyId)
        }
        return WishlistToggleInfo(propertyId, wished = true, wishCount = wishCountOf(propertyId))
    }

    @Transactional
    fun unwish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        requireProperty(propertyId)
        if (wishlistRepository.remove(loginId, propertyId)) {
            propertyRepository.decrementWishCount(propertyId)
        }
        return WishlistToggleInfo(propertyId, wished = false, wishCount = wishCountOf(propertyId))
    }

    private fun requireProperty(propertyId: Long) {
        propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
    }

    private fun wishCountOf(propertyId: Long): Int = propertyRepository.findWishCount(propertyId) ?: 0

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
