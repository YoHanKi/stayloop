package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 객실 타입 Aggregate Root. Property 는 raw `propertyId` 로만 참조하고(03 §0 — 물리적 FK 없음),
 * 참조 무결성은 애플리케이션이 책임진다.
 */
@Entity
@Table(name = "room_types")
class RoomTypeModel internal constructor(
    propertyId: Long,
    name: String,
    guestCount: GuestCount,
    bedConfig: BedConfig,
) : BaseEntity() {

    @Column(name = "property_id", nullable = false)
    var propertyId: Long = propertyId
        protected set

    @Column(name = "name", nullable = false, length = 100)
    var name: String = name
        protected set

    @Embedded
    var guestCount: GuestCount = guestCount
        protected set

    @Column(name = "bed_config", columnDefinition = "TEXT")
    var bedConfig: BedConfig = bedConfig
        protected set

    /** 요청 인원이 이 객실의 최대 인원을 넘으면 BAD_REQUEST. 0 명 이하도 거부한다. */
    fun checkGuestCount(requested: Int) {
        if (!guestCount.canAccommodate(requested)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "요청 인원($requested)이 객실 수용 범위(1~${guestCount.maxGuests})를 벗어났습니다.",
            )
        }
    }

    companion object {
        fun create(
            propertyId: Long,
            name: String,
            guestCount: GuestCount,
            bedConfig: BedConfig = BedConfig.EMPTY,
        ): RoomTypeModel {
            if (name.isBlank()) {
                throw CoreException(ErrorType.BAD_REQUEST, "객실 타입 이름은 비어 있을 수 없습니다.")
            }
            return RoomTypeModel(
                propertyId = propertyId,
                name = name,
                guestCount = guestCount,
                bedConfig = bedConfig,
            )
        }
    }
}
