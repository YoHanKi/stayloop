package com.stayloop.support.test

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.rate.DailyRoomRateModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InMemoryDailyRoomRateRepositoryTest {
    private val repository = InMemoryDailyRoomRateRepository()
    private val roomTypeId = 1L

    @DisplayName("findAllInRange 는 반-닫힌 구간 [from, to) 로 동작한다 — 체크아웃 당일 제외와 정합.")
    @Test
    fun shouldReturnHalfOpenRange() {
        repository.saveAll(
            listOf(
                DailyRoomRateModel.create(roomTypeId, LocalDate.of(2026, 5, 10), Money.of(100_000L)),
                DailyRoomRateModel.create(roomTypeId, LocalDate.of(2026, 5, 11), Money.of(110_000L)),
                DailyRoomRateModel.create(roomTypeId, LocalDate.of(2026, 5, 12), Money.of(120_000L)),
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
        repository.save(DailyRoomRateModel.create(roomTypeId, LocalDate.of(2026, 5, 10), Money.of(100_000L)))

        val result = repository.findAllInRange(roomTypeId, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10))

        assertThat(result).isEmpty()
    }

    @DisplayName("findAllInRange 는 다른 roomTypeId 의 행을 섞지 않는다.")
    @Test
    fun shouldFilterByRoomTypeId() {
        repository.save(DailyRoomRateModel.create(roomTypeId = 1L, date = LocalDate.of(2026, 5, 10), pricePerNight = Money.of(100_000L)))
        repository.save(DailyRoomRateModel.create(roomTypeId = 2L, date = LocalDate.of(2026, 5, 10), pricePerNight = Money.of(200_000L)))

        val result = repository.findAllInRange(roomTypeId = 1L, from = LocalDate.of(2026, 5, 10), to = LocalDate.of(2026, 5, 11))

        assertThat(result).hasSize(1)
        assertThat(result[0].roomTypeId).isEqualTo(1L)
    }

    @DisplayName("save 는 같은 자연키에 대해 upsert 동작 — 같은 (roomTypeId, date) 은 한 행만.")
    @Test
    fun shouldUpsertOnSameNaturalKey() {
        val date = LocalDate.of(2026, 5, 10)
        repository.save(DailyRoomRateModel.create(roomTypeId, date, Money.of(100_000L)))
        repository.save(DailyRoomRateModel.create(roomTypeId, date, Money.of(150_000L)))

        assertThat(repository.findById(roomTypeId, date))
            .isNotNull
            .extracting("pricePerNight").isEqualTo(Money.of(150_000L))
        assertThat(repository.findAllInRange(roomTypeId, date, date.plusDays(1))).hasSize(1)
    }
}
