package com.stayloop.domain.reservation.value

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.value.PropertyCategory
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

/**
 * 예약 시점의 숙소 정보 박제. 영수증·목록 표시에 필요한 최소 필드(id·이름·카테고리·도시)만 primitives 로 든다.
 * 식별자(id)도 스냅샷에 포함하므로 [ReservationModel] 에 propertyId 를 별도 컬럼으로 또 매핑하지 않는다(03 §6).
 */
@Embeddable
data class PropertySnapshot(
    @Column(name = "property_id", nullable = false)
    val propertyId: Long,
    @Column(name = "property_name", nullable = false, length = 100)
    val name: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "property_category", nullable = false, length = 20)
    val category: PropertyCategory,
    @Column(name = "property_city", nullable = false, length = 50)
    val city: String,
) {
    companion object {
        fun from(property: PropertyModel): PropertySnapshot =
            PropertySnapshot(
                propertyId = property.id,
                name = property.name.value,
                category = property.category,
                city = property.address.city,
            )
    }
}
