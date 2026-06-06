package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.PropertyPolicy
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * [PropertyPolicy] ↔ JSON. 중첩된 `CancellationPolicy` 와 `LocalTime` 까지 함께 직렬화한다. autoApply.
 */
@Converter(autoApply = true)
class PropertyPolicyConverter : AttributeConverter<PropertyPolicy, String> {
    override fun convertToDatabaseColumn(attribute: PropertyPolicy?): String? =
        attribute?.let { PropertyJsonMapper.instance.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): PropertyPolicy? {
        if (dbData.isNullOrBlank()) return null
        return PropertyJsonMapper.instance.readValue(dbData, PropertyPolicy::class.java)
    }
}
