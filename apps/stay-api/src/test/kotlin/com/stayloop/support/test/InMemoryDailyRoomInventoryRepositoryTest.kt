package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InMemoryDailyRoomInventoryRepositoryTest {
    private val repository = InMemoryDailyRoomInventoryRepository()

    private fun seed(roomTypeId: Long, vararg days: LocalDate) {
        repository.saveAll(days.map { DailyRoomInventoryModel(roomTypeId, it, totalRooms = 3) })
    }

    @DisplayName("기간 조회는 반-닫힌 구간 [from, to) — 체크아웃 당일을 포함하지 않는다.")
    @Test
    fun shouldExcludeCheckoutDate() {
        val d1 = LocalDate.of(2026, 6, 1)
        val d2 = LocalDate.of(2026, 6, 2)
        val checkout = LocalDate.of(2026, 6, 3)
        seed(1L, d1, d2, checkout)

        val range = repository.findAllInRange(1L, d1, checkout)

        assertThat(range.map { it.date }).containsExactly(d1, d2)
    }

    @DisplayName("같은 자연 키로 다시 저장하면 덮어쓴다(upsert).")
    @Test
    fun shouldUpsertByNaturalKey() {
        val date = LocalDate.of(2026, 6, 1)
        repository.save(DailyRoomInventoryModel(1L, date, totalRooms = 3, reservedRooms = 0))
        repository.save(DailyRoomInventoryModel(1L, date, totalRooms = 3, reservedRooms = 2))

        assertThat(repository.findById(1L, date)!!.reservedRooms).isEqualTo(2)
    }
}
