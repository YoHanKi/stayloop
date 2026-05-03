package com.stayloop.domain.wishlist

import java.io.Serializable

/**
 * `WishlistModel` 의 복합 PK. (`docs/design/03-class-diagram.md §3`, `04-erd.md §2.4`)
 *
 * **자연 키** `(userId, propertyId)` — 같은 사용자가 같은 숙소를 두 번 찜할 수 없음을 PK 자체로 표현.
 * `userId` 는 `users.id` (BIGINT FK), `propertyId` 는 `properties.id` (BIGINT FK).
 *
 * Repository **인터페이스 boundary** 는 `LoginId` 로 노출 (`docs/plan/week2-3.md §⑥`) — JPA `@IdClass` 가
 * 요구하는 BIGINT 표현은 본 클래스가 보유하고, `LoginId ↔ users.id` lookup 은 `WishlistRepositoryImpl` 책임.
 *
 * `Serializable` 은 Hibernate `@IdClass` 계약상 필수.
 */
data class WishlistId(
    val userId: Long = 0L,
    val propertyId: Long = 0L,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
