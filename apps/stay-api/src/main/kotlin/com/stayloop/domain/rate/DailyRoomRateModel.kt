package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 일자별 객실 요금. 재고와 같은 `(roomTypeId, date)` 자연 키 단위로, 1박 요금을 [Money] 로 든다.
 * 음수 요금은 [Money] 가 막는다.
 */
@Entity
@Table(name = "daily_room_rates")
@IdClass(DailyRoomRateId::class)
class DailyRoomRateModel(
    roomTypeId: Long,
    date: LocalDate,
    pricePerNight: Money,
) {
    @Id
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long = roomTypeId

    @Id
    @Column(name = "date", nullable = false)
    val date: LocalDate = date

    @Embedded
    @AttributeOverride(
        name = "amount",
        column = Column(name = "price_per_night", nullable = false, precision = 19, scale = 2),
    )
    var pricePerNight: Money = pricePerNight
        protected set
}
