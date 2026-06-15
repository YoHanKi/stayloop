package com.stayloop.domain.wishlist

import com.stayloop.domain.user.value.LoginId
import java.time.LocalDateTime

/**
 * 찜 Repository. 경계는 `users.id` 가 아니라 [LoginId] 로 노출하고, `LoginId ↔ users.id` 변환은
 * 구현(`WishlistRepositoryImpl`)이 캡슐화한다(week2-3 §⑥).
 */
interface WishlistRepository {
    /**
     * 찜을 추가한다. **새로 추가됐으면 true, 이미 있으면 false**(멱등). 이 사실로 호출자가 카운터 증가 여부를 정한다 —
     * "추가됐을 때만 +1" 이라야 동시 중복 요청에도 카운터가 행 수와 일치한다(04-b §5-4). 사용자가 없으면 NOT_FOUND.
     */
    fun add(loginId: LoginId, propertyId: Long, wishedAt: LocalDateTime): Boolean

    /** 찜을 제거한다. **실제로 제거됐으면 true, 없으면 false**(멱등). 호출자가 이 사실로 카운터 감소 여부를 정한다. */
    fun remove(loginId: LoginId, propertyId: Long): Boolean

    /** 사용자의 찜 목록을 `wishedAt` 내림차순으로 페이지 조회한다. 사용자가 없으면 빈 목록. */
    fun findByUserId(loginId: LoginId, page: Int, size: Int): List<WishlistModel>
}
