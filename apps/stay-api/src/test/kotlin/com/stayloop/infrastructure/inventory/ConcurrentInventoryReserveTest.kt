package com.stayloop.infrastructure.inventory

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryService
import com.stayloop.support.error.CoreException
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.testcontainers.RedisTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 04-a §7 / 04-b Q4 — 동시성 정합성은 H2 가 아니라 실제 MySQL(Testcontainers)에서 증명한다.
 * 조건부 원자 UPDATE(QueryDSL) + 도메인 서비스의 all-or-nothing 판단으로 더블부킹·초과 차감·부분 차감 부재와,
 * 동시 차감/복원 후 카운터 정합(reserved = 초기 + 성공 차감 − 성공 복원)을 본다.
 *
 * 운영 어댑터([DailyRoomInventoryRepositoryImpl])와 도메인 서비스를 그대로 조립해 실제 SQL 로 검증한다.
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class ConcurrentInventoryReserveTest
    @Autowired
    constructor(
        private val jpaRepository: DailyRoomInventoryJpaRepository,
        queryFactory: JPAQueryFactory,
        transactionManager: PlatformTransactionManager,
        private val databaseCleanUp: DatabaseCleanUp,
    ) {
        private val txTemplate = TransactionTemplate(transactionManager)
        private val service = DailyRoomInventoryService(DailyRoomInventoryRepositoryImpl(jpaRepository, queryFactory))

        private val roomTypeId = 1L
        private val d1 = LocalDate.of(2026, 6, 1)
        private val d2 = LocalDate.of(2026, 6, 2)
        private val d3 = LocalDate.of(2026, 6, 3)

        @AfterEach
        fun tearDown() {
            databaseCleanUp.truncateAllTables()
        }

        @DisplayName("마지막 N 실에 동시 차감이 몰려도 성공 수 = 재고, 초과·음수 없음.")
        @Test
        fun singleDate_noOverbooking() {
            val stock = 10
            val threads = 60
            jpaRepository.save(DailyRoomInventoryModel(roomTypeId, d1, totalRooms = stock))

            val successes = runConcurrent(threads) { attempt { service.reserve(roomTypeId, listOf(d1)) } }

            assertThat(successes).isEqualTo(stock)
            val final = inventory(d1)
            assertThat(final.reservedRooms).isEqualTo(stock)
            assertThat(final.available()).isEqualTo(0)
        }

        @DisplayName("다일자 동시 차감에서 부분 차감 없이 전 일자 성공 수 = 재고.")
        @Test
        fun multiDate_noPartialDecrement() {
            val stock = 5
            val threads = 40
            val dates = listOf(d1, d2, d3)
            jpaRepository.saveAll(dates.map { DailyRoomInventoryModel(roomTypeId, it, totalRooms = stock) })

            val successes = runConcurrent(threads) { attempt { service.reserve(roomTypeId, dates) } }

            assertThat(successes).isEqualTo(stock)
            assertThat(dates.map { inventory(it).reservedRooms }).containsOnly(stock)
        }

        @DisplayName("동시 차감·복원 후 카운터가 정합하고 항상 [0, total] 안에 있다.")
        @Test
        fun reserveAndRelease_consistent() {
            val total = 20
            val initialReserved = 10
            val reserveThreads = 20
            val releaseThreads = 20
            jpaRepository.save(DailyRoomInventoryModel(roomTypeId, d1, totalRooms = total, reservedRooms = initialReserved))

            val reserved = AtomicInteger(0)
            val released = AtomicInteger(0)
            val tasks =
                List(reserveThreads) { { if (attempt { service.reserve(roomTypeId, listOf(d1)) }) reserved.incrementAndGet() } } +
                    List(releaseThreads) { { if (attempt { service.release(roomTypeId, listOf(d1)) }) released.incrementAndGet() } }

            runConcurrentTasks(tasks.shuffled())

            val final = inventory(d1)
            assertThat(final.reservedRooms).isEqualTo(initialReserved + reserved.get() - released.get())
            assertThat(final.reservedRooms).isBetween(0, total)
        }

        /** 락 대기 타임아웃·데드락 victim 은 일시 충돌이므로 짧게 재시도하고, 도메인 거절(CONFLICT 등)은 즉시 실패로 본다. */
        private fun attempt(action: () -> Unit): Boolean {
            repeat(MAX_TRIES) {
                try {
                    txTemplate.executeWithoutResult { action() }
                    return true
                } catch (e: CoreException) {
                    return false
                } catch (e: PessimisticLockingFailureException) {
                    // 다음 루프에서 재시도
                }
            }
            return false
        }

        private fun runConcurrent(threads: Int, task: () -> Boolean): Int {
            val successes = AtomicInteger(0)
            runConcurrentTasks(List(threads) { { if (task()) successes.incrementAndGet() } })
            return successes.get()
        }

        private fun runConcurrentTasks(tasks: List<() -> Unit>) {
            val pool = Executors.newFixedThreadPool(tasks.size)
            try {
                val start = CountDownLatch(1)
                val futures =
                    tasks.map { task ->
                        pool.submit {
                            start.await()
                            task()
                        }
                    }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        private fun inventory(date: LocalDate): DailyRoomInventoryModel =
            jpaRepository.findById(DailyRoomInventoryId(roomTypeId, date)).orElseThrow()

        companion object {
            private const val MAX_TRIES = 10
        }
    }
