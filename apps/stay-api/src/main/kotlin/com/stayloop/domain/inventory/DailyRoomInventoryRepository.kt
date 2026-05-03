package com.stayloop.domain.inventory

import java.time.LocalDate

/**
 * `DailyRoomInventory` 의 도메인 Repository 인터페이스.
 * 구현체는 `infrastructure/inventory/DailyRoomInventoryRepositoryImpl` (Spring Data 위임).
 *
 * **`findAllInRange` 는 반-닫힌 구간 `[from, to)`** — `StayPeriod.datesToReserve()` (체크아웃 당일 제외)
 * 와 정합. `from == to` 면 빈 리스트.
 */
interface DailyRoomInventoryRepository {
    /**
     * 자연키 (`roomTypeId`, `date`) 단건 조회. 없으면 null.
     */
    fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel?

    /**
     * 반-닫힌 구간 `[from, to)` 의 일자별 재고 목록. 누락된 일자는 결과에 포함되지 않음
     * (Reservation Facade 가 누락을 가용 0 으로 해석할지 거절할지 결정).
     */
    fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel>

    /**
     * 다건 저장. 차감/복원 후 일괄 영속화에 사용 (`feature/reservation-facade` 의 `saveAll`).
     */
    fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel>

    /**
     * 단건 저장 (어드민 일괄 등록 / 단일 행 갱신용).
     */
    fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel
}
