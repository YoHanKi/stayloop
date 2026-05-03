package com.stayloop.domain.rate

import java.time.LocalDate

/**
 * `DailyRoomRate` 의 도메인 Repository 인터페이스.
 * 구현체는 `infrastructure/rate/DailyRoomRateRepositoryImpl` (Spring Data 위임).
 *
 * **`findAllInRange` 는 반-닫힌 구간 `[from, to)`** — `StayPeriod.datesToReserve()` (체크아웃 당일 제외)
 * 와 정합. `from == to` 면 빈 리스트. Inventory Repository 와 동일 의미론.
 */
interface DailyRoomRateRepository {
    /**
     * 자연키 (`roomTypeId`, `date`) 단건 조회. 없으면 null.
     */
    fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel?

    /**
     * 반-닫힌 구간 `[from, to)` 의 일자별 요금 목록. 누락된 일자는 결과에 포함되지 않음
     * (Reservation Facade 가 누락을 0원으로 해석할지 거절할지 결정 — 본 라운드는 도메인이 모름).
     */
    fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomRateModel>

    /**
     * 다건 저장. 어드민 일괄 등록 / Reservation Facade 의 합산 후 영속화에 사용.
     */
    fun saveAll(rates: Collection<DailyRoomRateModel>): List<DailyRoomRateModel>

    /**
     * 단건 저장 — upsert 의미.
     */
    fun save(rate: DailyRoomRateModel): DailyRoomRateModel
}
