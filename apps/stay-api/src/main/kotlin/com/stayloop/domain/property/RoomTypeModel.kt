package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.Name
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 객실 타입 Aggregate Root. (`docs/design/03-class-diagram.md §1`)
 * Property 와 별도 AR — 라이프사이클 분리 (어드민이 객실 타입만 단독 추가/삭제).
 */
@Entity
@Table(
    name = "room_types",
    indexes = [Index(name = "idx_room_types_property_id", columnList = "property_id")],
)
class RoomTypeModel internal constructor(
    propertyId: Long,
    name: Name,
    guestCount: GuestCount,
    bedConfig: BedConfig,
) : BaseEntity() {

    @Column(name = "property_id", nullable = false)
    var propertyId: Long = propertyId
        protected set

    @Embedded
    var name: Name = name
        protected set

    @Embedded
    var guestCount: GuestCount = guestCount
        protected set

    // BedConfigConverter 가 autoApply 로 처리.
    @Column(name = "bed_config", columnDefinition = "JSON", nullable = false)
    var bedConfig: BedConfig = bedConfig
        protected set

    init {
        if (propertyId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "propertyId 는 양수여야 합니다 (영속화된 Property 의 id).")
        }
    }

    /**
     * 예약 요청 인원이 객실 최대 인원을 초과하면 거부.
     * AC-5 의 도메인 레벨 검증 지점 (`docs/design/01-requirements.md §2.1`).
     */
    fun checkGuestCount(requested: Int) {
        if (requested <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 인원은 1명 이상이어야 합니다.")
        }
        if (requested > guestCount.max) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "요청 인원($requested) 이 객실 최대 인원(${guestCount.max}) 을 초과합니다.",
            )
        }
    }

    companion object {
        fun create(
            propertyId: Long,
            name: Name,
            guestCount: GuestCount,
            bedConfig: BedConfig,
        ): RoomTypeModel = RoomTypeModel(propertyId, name, guestCount, bedConfig)
    }
}
