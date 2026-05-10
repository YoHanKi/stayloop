package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InMemoryDailyRoomInventoryRepositoryTest {
    private val repository = InMemoryDailyRoomInventoryRepository()
    private val roomTypeId = 1L

    @DisplayName("findAllInRange 는 반-닫힌 구간 [from, to) 로 동작한다 — 체크아웃 당일 제외와 정합.")
    @Test
    fun shouldReturnHalfOpenRange() {
        repository.saveAll(
            listOf(
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 10), totalRooms = 5),
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 11), totalRooms = 5),
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 12), totalRooms = 5),
            ),
        )

        // [5/10, 5/12) — 5/10, 5/11 두 일자만 포함
        val result = repository.findAllInRange(roomTypeId, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 12))

        assertThat(result).extracting("date")
            .containsExactly(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 11))
    }

    @DisplayName("findAllInRange 는 from == to 이면 빈 리스트를 반환한다.")
    @Test
    fun shouldReturnEmpty_whenFromEqualsTo() {
        repository.save(DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 10), totalRooms = 5))

        val result = repository.findAllInRange(roomTypeId, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10))

        assertThat(result).isEmpty()
    }

    @DisplayName("findAllInRange 는 다른 roomTypeId 의 행을 섞지 않는다.")
    @Test
    fun shouldFilterByRoomTypeId() {
        repository.save(DailyRoomInventoryModel.create(roomTypeId = 1L, date = LocalDate.of(2026, 5, 10), totalRooms = 5))
        repository.save(DailyRoomInventoryModel.create(roomTypeId = 2L, date = LocalDate.of(2026, 5, 10), totalRooms = 5))

        val result = repository.findAllInRange(roomTypeId = 1L, from = LocalDate.of(2026, 5, 10), to = LocalDate.of(2026, 5, 11))

        assertThat(result).hasSize(1)
        assertThat(result[0].roomTypeId).isEqualTo(1L)
    }

    @DisplayName("findInventoriesForUpdate 는 인자 순서와 무관하게 항상 date ASC 로 정렬해 반환한다 — 다일자 락 순서 정합.")
    @Test
    fun shouldReturnSortedByDateAsc_regardlessOfInputOrder() {
        repository.saveAll(
            listOf(
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 10), totalRooms = 5),
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 11), totalRooms = 5),
                DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 12), totalRooms = 5),
            ),
        )

        val result = repository.findInventoriesForUpdate(
            roomTypeId,
            // 일부러 역순 입력 — 구현이 입력 순서를 그대로 따르면 데드락 회피 정렬 가드가 깨진다.
            listOf(LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 11)),
        )

        assertThat(result).extracting("date")
            .containsExactly(
                LocalDate.of(2026, 5, 10),
                LocalDate.of(2026, 5, 11),
                LocalDate.of(2026, 5, 12),
            )
    }

    @DisplayName("findInventoriesForUpdate 는 dates 가 비어있으면 빈 리스트를 반환한다 (no-op).")
    @Test
    fun shouldReturnEmpty_whenDatesEmpty() {
        repository.save(DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 10), totalRooms = 5))

        assertThat(repository.findInventoriesForUpdate(roomTypeId, emptyList())).isEmpty()
    }

    @DisplayName("findInventoriesForUpdate 는 누락된 일자를 결과에 포함시키지 않는다 (호출자가 BAD_REQUEST 처리).")
    @Test
    fun shouldNotIncludeMissingDates() {
        repository.save(DailyRoomInventoryModel.create(roomTypeId, LocalDate.of(2026, 5, 10), totalRooms = 5))

        val result = repository.findInventoriesForUpdate(
            roomTypeId,
            listOf(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 11)),
        )

        assertThat(result).hasSize(1)
        assertThat(result[0].date).isEqualTo(LocalDate.of(2026, 5, 10))
    }

    @DisplayName("findInventoriesForUpdate 는 다른 roomTypeId 의 행을 섞지 않는다.")
    @Test
    fun shouldFilterByRoomTypeId_inFindInventoriesForUpdate() {
        val date = LocalDate.of(2026, 5, 10)
        repository.save(DailyRoomInventoryModel.create(roomTypeId = 1L, date = date, totalRooms = 5))
        repository.save(DailyRoomInventoryModel.create(roomTypeId = 2L, date = date, totalRooms = 5))

        val result = repository.findInventoriesForUpdate(roomTypeId = 1L, dates = listOf(date))

        assertThat(result).hasSize(1)
        assertThat(result[0].roomTypeId).isEqualTo(1L)
    }

    @DisplayName("save 는 같은 자연키에 대해 upsert 동작 — 같은 (roomTypeId, date) 은 한 행만.")
    @Test
    fun shouldUpsertOnSameNaturalKey() {
        val date = LocalDate.of(2026, 5, 10)
        repository.save(DailyRoomInventoryModel.create(roomTypeId, date, totalRooms = 5))
        repository.save(DailyRoomInventoryModel.create(roomTypeId, date, totalRooms = 7))

        assertThat(repository.findById(roomTypeId, date))
            .isNotNull
            .extracting("totalRooms").isEqualTo(7)
        assertThat(repository.findAllInRange(roomTypeId, date, date.plusDays(1))).hasSize(1)
    }
}
