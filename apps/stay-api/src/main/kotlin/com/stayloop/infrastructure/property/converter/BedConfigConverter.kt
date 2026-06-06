package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.core.type.TypeReference
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * [BedConfig] ↔ JSON 오브젝트(`{"DOUBLE":1}`). autoApply.
 */
@Converter(autoApply = true)
class BedConfigConverter : AttributeConverter<BedConfig, String> {
    override fun convertToDatabaseColumn(attribute: BedConfig?): String =
        PropertyJsonMapper.instance.writeValueAsString(attribute?.beds ?: emptyMap<BedType, Int>())

    override fun convertToEntityAttribute(dbData: String?): BedConfig {
        if (dbData.isNullOrBlank()) return BedConfig.EMPTY
        val beds: Map<BedType, Int> =
            PropertyJsonMapper.instance.readValue(dbData, object : TypeReference<Map<BedType, Int>>() {})
        return BedConfig(beds)
    }
}
