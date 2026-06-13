package com.stayloop.domain.rate

import java.io.Serializable
import java.time.LocalDate

/**
 * [DailyRoomRateModel] 의 `@IdClass` 복합 키 `(roomTypeId, date)`.
 */
data class DailyRoomRateId(
    val roomTypeId: Long = 0,
    val date: LocalDate = LocalDate.MIN,
) : Serializable
