package com.stayloop.domain.rate

import java.io.Serializable
import java.time.LocalDate

/**
 * `DailyRoomRateModel` 의 복합 PK. (`docs/design/03-class-diagram.md §2`, `04-erd.md §2.2`)
 *
 * **자연 키 채택** — Inventory 와 동일 정책. 도메인 시그니처 `findById(roomTypeId, date)` /
 * `findAllInRange(roomTypeId, from, to)` 가 자연스러우려면 `@IdClass` 가 `@EmbeddedId` 보다
 * 호출자 코드를 줄인다 (`docs/plan/week2-3.md §⑤` 결정).
 *
 * `Serializable` 은 Hibernate `@IdClass` 계약상 필수.
 */
data class DailyRoomRateId(
    val roomTypeId: Long = 0L,
    val date: LocalDate = LocalDate.MIN,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
