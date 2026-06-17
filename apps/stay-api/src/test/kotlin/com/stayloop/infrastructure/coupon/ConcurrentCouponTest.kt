package com.stayloop.infrastructure.coupon

import com.stayloop.application.coupon.CouponFacade
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.coupon.CouponService
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.user.value.LoginId
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
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 04-a §6.3·§7 / 04-b Q4 — 쿠폰 발급(핫스팟)·사용(저경합)의 동시성 정합을 실제 MySQL(Testcontainers)에서 증명한다.
 * 발급은 한정 수량 초과 없음·동일인 중복 발급 1건, 사용은 동일 쿠폰 중복 사용 1회를 본다.
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class ConcurrentCouponTest
    @Autowired
    constructor(
        private val couponFacade: CouponFacade,
        private val couponService: CouponService,
        private val couponTemplateRepository: CouponTemplateRepository,
        private val issuedCouponJpaRepository: IssuedCouponJpaRepository,
        transactionManager: PlatformTransactionManager,
        private val databaseCleanUp: DatabaseCleanUp,
    ) {
        private val txTemplate = TransactionTemplate(transactionManager)
        private val now = LocalDateTime.of(2026, 6, 1, 10, 0)

        @AfterEach
        fun tearDown() {
            databaseCleanUp.truncateAllTables()
        }

        @DisplayName("한정 수량보다 많은 동시 발급에도 발급 수 = 한정 수량(초과 발급 없음).")
        @Test
        fun issue_noOverIssue() {
            val limit = 10
            val users = 60
            val templateId = saveTemplate(limit)

            val success = runConcurrent(users) { idx ->
                attempt { couponFacade.issue(IssueCouponCommand(LoginId("user%05d".format(idx)), templateId)) }
            }

            assertThat(success).isEqualTo(limit)
            assertThat(couponTemplateRepository.findById(templateId)!!.issuedCount).isEqualTo(limit)
        }

        @DisplayName("동일인이 같은 쿠폰을 동시에 여러 번 발급해도 1 건만 발급되고 발급 수도 1 이다.")
        @Test
        fun issue_duplicateSameUser() {
            val templateId = saveTemplate(total = 100)
            val user = LoginId("dupuser01")

            val success = runConcurrent(30) { attempt { couponFacade.issue(IssueCouponCommand(user, templateId)) } }

            assertThat(success).isEqualTo(1)
            assertThat(couponTemplateRepository.findById(templateId)!!.issuedCount).isEqualTo(1)
        }

        @DisplayName("같은 발급 쿠폰을 동시에 여러 번 사용해도 1 번만 사용 처리된다.")
        @Test
        fun use_singleUse() {
            val templateId = saveTemplate(total = 10)
            val issuedId = couponFacade.issue(IssueCouponCommand(LoginId("useuser01"), templateId)).issuedCouponId

            val success = runConcurrent(30) { attempt { txTemplate.executeWithoutResult { couponService.use(issuedId, now) } } }

            assertThat(success).isEqualTo(1)
            assertThat(issuedCouponJpaRepository.findById(issuedId).orElseThrow().status).isEqualTo(CouponStatus.USED)
        }

        private fun saveTemplate(total: Int): Long =
            couponTemplateRepository.save(
                CouponTemplateModel("선착순", DiscountValue.of(DiscountType.FIXED, 1_000), total),
            ).id

        /** 락 대기 타임아웃·데드락 victim 은 짧게 재시도하고, 도메인 거절(CONFLICT 등)은 즉시 실패로 본다. */
        private fun attempt(action: () -> Unit): Boolean {
            repeat(MAX_TRIES) {
                try {
                    action()
                    return true
                } catch (e: CoreException) {
                    return false
                } catch (e: PessimisticLockingFailureException) {
                    // 다음 루프에서 재시도
                }
            }
            return false
        }

        private fun runConcurrent(n: Int, task: (Int) -> Boolean): Int {
            val successes = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(n)
            try {
                val start = CountDownLatch(1)
                val futures =
                    (0 until n).map { idx ->
                        pool.submit {
                            start.await()
                            if (task(idx)) successes.incrementAndGet()
                        }
                    }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            return successes.get()
        }

        companion object {
            private const val MAX_TRIES = 10
        }
    }
