package com.stayloop.domain.inventory

import java.io.Serializable
import java.time.LocalDate

/**
 * [DailyRoomInventoryModel] 의 `@IdClass` 복합 키 `(roomTypeId, date)`.
 * JPA 의 @IdClass 규약상 [Serializable] 이어야 하고 모델의 `@Id` 필드명과 1:1 로 맞아야 한다.
 */
data class DailyRoomInventoryId(
    val roomTypeId: Long = 0,
    val date: LocalDate = LocalDate.MIN,
) : Serializable
