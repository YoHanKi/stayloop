package com.stayloop.domain.inventory

import java.io.Serializable
import java.time.LocalDate

/**
 * `DailyRoomInventoryModel` 의 복합 PK. (`docs/design/03-class-diagram.md §2`, `04-erd.md §2.1`)
 *
 * **자연 키 채택** — 도메인 시그니처 `findById(roomTypeId, date)` 가 자연스러우려면
 * `@IdClass` 가 `@EmbeddedId` 보다 호출자 코드를 줄인다 (`docs/plan/week2-3.md` 결정).
 *
 * `Serializable` 은 Hibernate `@IdClass` 계약상 필수.
 */
data class DailyRoomInventoryId(
    val roomTypeId: Long = 0L,
    val date: LocalDate = LocalDate.MIN,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
