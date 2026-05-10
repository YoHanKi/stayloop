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
     * **비관적 락 (PESSIMISTIC_WRITE)** 으로 다일자 재고 행을 락 획득과 함께 조회한다.
     * (`docs/plan/week4.md` ③ Phase A, `decision.md` D-1)
     *
     * **데드락 회피의 본질** — `dates` 인자가 *어떤 순서로 들어와도* 구현체는 **`date ASC` 정렬된 락 순서**
     * 를 강제한다 (QueryDSL `orderBy(d.date.asc())`). 다일자 예약이 `5/10~5/12` 와 `5/11~5/13` 처럼 겹쳐
     * 동시 진입해도 모두 같은 순서로 행 락을 획득하므로 cycle 이 만들어지지 않는다.
     *
     * **호출 계약**:
     * - 반드시 `@Transactional` 안에서 호출 (락은 TX 종료 시 해제 — TX 가 없으면 락이 즉시 풀려 race 발생).
     * - `dates` 는 *재고 차감 대상 일자* — 빈 리스트이면 빈 결과 (no-op). null 금지.
     * - 누락된 일자는 결과에 포함되지 않으므로 호출자(`ReservationService`) 가 누락을 BAD_REQUEST 로 거절.
     *
     * **NOWAIT 미적용 (fast-follow #1 / `db-lock-low-level.md` LQ3)** — Phase 0 E-2 가 *분산 부하* 환경에서
     * NOWAIT 의 처리량 우위 (p95 37ms vs default 9_823ms + 38% pool exhaustion) 를 확인했으나, latch 동기화된
     * *exactly simultaneous* 도착 패턴에서는 모든 스레드의 lock 획득이 동시에 거절되어 success=0 의 pathological
     * 결과가 재현되었다. 본 라운드는 default `innodb_lock_wait_timeout` 의존하며, NOWAIT 도입은 *부하 환경에서
     * pool exhaustion 이 실제 위협으로 합류* 하는 시점 (5주차+) 에 별도 commit 으로 회수한다.
     */
    fun findInventoriesForUpdate(
        roomTypeId: Long,
        dates: List<LocalDate>,
    ): List<DailyRoomInventoryModel>

    /**
     * 다건 저장. 차감/복원 후 일괄 영속화에 사용 (`feature/reservation-facade` 의 `saveAll`).
     */
    fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel>

    /**
     * 단건 저장 (어드민 일괄 등록 / 단일 행 갱신용).
     */
    fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel
}
