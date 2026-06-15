package com.stayloop.application.wishlist

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.infrastructure.wishlist.WishlistJpaRepository
import com.stayloop.support.test.UserFixture
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 04-b §5-4 검증 — 비정규화 `wishCount` 의 동시 증감 정합을 실제 MySQL(Testcontainers)에서 본다.
 * 동시 찜/취소 후 `wishCount` 가 실제 찜 행 수와 일치하는지(중복 요청 드리프트 부재).
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class ConcurrentWishlistTest
    @Autowired
    constructor(
        private val wishlistFacade: WishlistFacade,
        private val propertyRepository: PropertyRepository,
        private val userRepository: UserRepository,
        private val wishlistJpaRepository: WishlistJpaRepository,
        private val databaseCleanUp: DatabaseCleanUp,
    ) {
        @AfterEach
        fun tearDown() {
            databaseCleanUp.truncateAllTables()
        }

        @DisplayName("같은 사용자가 동시에 여러 번 찜해도 행은 하나, wishCount 도 1 이다(드리프트 없음).")
        @Test
        fun sameUser_repeatedWish_noDrift() {
            val propertyId = seedProperty()
            val user = seedUser("wuser0001")

            runConcurrent(20) { attempt { wishlistFacade.wish(user, propertyId) } }

            assertThat(wishCount(propertyId)).isEqualTo(1)
            assertThat(wishlistJpaRepository.count()).isEqualTo(1L)
        }

        @DisplayName("서로 다른 사용자가 동시에 찜하면 wishCount = 사용자 수다.")
        @Test
        fun manyUsers_wish_countEqualsUsers() {
            val propertyId = seedProperty()
            val users = (1..30).map { seedUser("wuser%05d".format(it)) }

            runConcurrent(users.size) { idx -> attempt { wishlistFacade.wish(users[idx], propertyId) } }

            assertThat(wishCount(propertyId)).isEqualTo(users.size)
            assertThat(wishlistJpaRepository.count()).isEqualTo(users.size.toLong())
        }

        @DisplayName("같은 사용자의 동시 찜/취소가 섞여도 wishCount 는 실제 행 수와 일치한다.")
        @Test
        fun mixedToggle_countMatchesRows() {
            val propertyId = seedProperty()
            val user = seedUser("wuser0001")

            val tasks = List(20) { { attempt { wishlistFacade.wish(user, propertyId) } } } +
                List(20) { { attempt { wishlistFacade.unwish(user, propertyId) } } }
            runConcurrentTasks(tasks.shuffled())

            assertThat(wishCount(propertyId)).isEqualTo(wishlistJpaRepository.count().toInt())
            assertThat(wishCount(propertyId)).isBetween(0, 1)
        }

        private fun seedProperty(): Long =
            propertyRepository.save(
                PropertyModel.create(
                    name = PropertyName("스테이루프 호텔"),
                    category = PropertyCategory.HOTEL,
                    address = Address("seoul", "서울특별시 중구 세종대로 110"),
                    policy = PropertyPolicy.standard(),
                ),
            ).id

        private fun seedUser(loginId: String): LoginId {
            UserFixture.save(userRepository, loginId)
            return LoginId(loginId)
        }

        private fun wishCount(propertyId: Long): Int = propertyRepository.findWishCount(propertyId)!!

        private fun attempt(action: () -> Unit): Boolean {
            repeat(MAX_TRIES) {
                try {
                    action()
                    return true
                } catch (e: PessimisticLockingFailureException) {
                    // 일시적 락 경합 — 재시도
                }
            }
            return false
        }

        private fun runConcurrent(n: Int, task: (Int) -> Boolean) {
            runConcurrentTasks((0 until n).map { idx -> { task(idx) } })
        }

        private fun runConcurrentTasks(tasks: List<() -> Any?>) {
            val pool = Executors.newFixedThreadPool(tasks.size)
            try {
                val start = CountDownLatch(1)
                val futures = tasks.map { task ->
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

        companion object {
            private const val MAX_TRIES = 10
        }
    }
