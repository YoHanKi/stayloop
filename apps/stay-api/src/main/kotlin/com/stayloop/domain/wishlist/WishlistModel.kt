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
 * 사용자 ↔ 숙소 찜 관계. (`docs/design/03-class-diagram.md §3`, `04-erd.md §2.4`)
 * **자연 키** `(userId, propertyId)` — 같은 사용자가 같은 숙소를 두 행으로 찜할 수 없음을 PK 로 표현.
 * 찜 단위는 **객실(RoomType) 이 아니라 숙소(Property)** — 요구사항에 명시.
 *
 * 본 모델은 JPA 엔티티이므로 `userId` 는 `users.id` (BIGINT FK) 를 직접 보유한다 — `LoginId` 와의
 * 변환은 `WishlistRepositoryImpl` 의 책임 (`docs/plan/week2-3.md §⑥`). 도메인 서비스 / Facade 는 boundary 에서
 * `LoginId` 를 주고받지만, JPA 엔티티 본체는 FK 매핑상 BIGINT 를 갖는다 (`DailyRoomRateModel.roomTypeId: Long`
 * 패턴과 일관).
 *
 * 도메인 어휘 매핑:
 * - 도메인 필드 `wishedAt` ↔ DB 컬럼 `created_at` — ERD 의 audit 컬럼명과 도메인 어휘를 분리.
 *   `created_at` 은 일반화된 행 생성 시각이지만, 본 도메인에서 행 생성 = 찜 시점이므로 `wishedAt` 으로 노출한다.
 *
 * 도메인 레벨 가드:
 * - `userId > 0` (영속화된 User 참조)
 * - `propertyId > 0` (영속화된 Property 참조)
 *
 * **인덱스 정책**: `@IdClass` 의 PK `(userId, propertyId)` 자체가 동일 컬럼/순서의 인덱스를 제공한다.
 * `@Table(indexes = ...)` 로 같은 키를 한 번 더 명시하면 보조 인덱스가 중복 생성되어
 * 쓰기 amplification / 스토리지 비용이 증가한다 (verify-code §17).
 *
 * **wishCount 동기화**: Property 의 캐시 카운터(`Property.wishCount`) 와의 동시 갱신은
 * `WishlistFacade` 의 트랜잭션 책임 (`docs/plan/week2-3.md §⑨`) — 본 도메인 모델은 카운터를 인지하지 않는다.
 */
@Entity
@Table(name = "wishlists")
@IdClass(WishlistId::class)
class WishlistModel internal constructor(
    userId: Long,
    propertyId: Long,
    wishedAt: LocalDateTime,
) {

    @Id
    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @Id
    @Column(name = "property_id", nullable = false)
    var propertyId: Long = propertyId
        protected set

    @Column(name = "created_at", nullable = false)
    var wishedAt: LocalDateTime = wishedAt
        protected set

    init {
        if (userId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "userId 는 양수여야 합니다 (영속화된 User 의 id).")
        }
        if (propertyId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "propertyId 는 양수여야 합니다 (영속화된 Property 의 id).")
        }
    }

    companion object {
        fun create(
            userId: Long,
            propertyId: Long,
            wishedAt: LocalDateTime,
        ): WishlistModel = WishlistModel(
            userId = userId,
            propertyId = propertyId,
            wishedAt = wishedAt,
        )
    }
}
