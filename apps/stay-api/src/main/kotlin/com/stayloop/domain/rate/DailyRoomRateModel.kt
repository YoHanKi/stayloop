package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 일자별 객실 요금 (1박 단위). (`docs/design/03-class-diagram.md §2`, `04-erd.md §2.2`)
 * **자연 키** `(roomTypeId, date)` — 같은 객실 타입의 같은 일자가 두 행이 될 수 없다.
 *
 * `Money` VO 를 `@Embedded` 로 임베드하고 `@AttributeOverride` 로 컬럼명을 `price_per_night` 으로 명시.
 * Money 자체에는 컬럼명을 박지 않아 다른 호스트(쿠폰/세금 등) 에서 재사용 가능하게 한다.
 *
 * 도메인 레벨 가드:
 * - 음수 가격은 `Money.init` 에서 차단 (도메인 1차)
 * - `roomTypeId > 0` (영속화된 RoomType 참조)
 *
 * **인덱스 정책**: `@IdClass` 의 PK `(roomTypeId, date)` 자체가 동일 컬럼/순서의 인덱스를 제공한다.
 * `@Table(indexes = ...)` 로 같은 키를 한 번 더 명시하면 보조 인덱스가 중복 생성되어
 * 쓰기 amplification / 스토리지 비용이 증가한다 (verify-code §17).
 */
@Entity
@Table(name = "daily_room_rates")
@IdClass(DailyRoomRateId::class)
class DailyRoomRateModel internal constructor(
    roomTypeId: Long,
    date: LocalDate,
    pricePerNight: Money,
) {

    @Id
    @Column(name = "room_type_id", nullable = false)
    var roomTypeId: Long = roomTypeId
        protected set

    @Id
    @Column(name = "date", nullable = false)
    var date: LocalDate = date
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "price_per_night", nullable = false))
    var pricePerNight: Money = pricePerNight
        protected set

    init {
        if (roomTypeId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "roomTypeId 는 양수여야 합니다 (영속화된 RoomType 의 id).")
        }
        // pricePerNight 의 음수 가드는 Money.init 가 책임 — 여기서 중복 검증하지 않는다.
    }

    companion object {
        fun create(
            roomTypeId: Long,
            date: LocalDate,
            pricePerNight: Money,
        ): DailyRoomRateModel = DailyRoomRateModel(
            roomTypeId = roomTypeId,
            date = date,
            pricePerNight = pricePerNight,
        )
    }
}
