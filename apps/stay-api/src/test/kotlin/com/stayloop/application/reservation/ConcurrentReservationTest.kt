package com.stayloop.application.reservation

import com.stayloop.application.coupon.CouponFacade
import com.stayloop.application.coupon.command.CreateCouponTemplateCommand
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.infrastructure.coupon.IssuedCouponJpaRepository
import com.stayloop.infrastructure.inventory.DailyRoomInventoryJpaRepository
import com.stayloop.infrastructure.rate.DailyRoomRateJpaRepository
import com.stayloop.support.error.CoreException
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.testcontainers.RedisTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.PessimisticLockingFailureException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 04-b §5-3 검증 — 예약 트랜잭션 통합의 정합을 실제 MySQL(Testcontainers)에서 본다.
 * 부분 실패(쿠폰 사용 불가) 시 재고 차감까지 전체 롤백되는지, 같은 쿠폰 동시 예약 시 한 건만 반영되는지.
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class ConcurrentReservationTest
    @Autowired
    constructor(
        private val reservationFacade: ReservationFacade,
        private val couponFacade: CouponFacade,
        private val propertyRepository: PropertyRepository,
        private val roomTypeRepository: RoomTypeRepository,
        private val inventoryJpaRepository: DailyRoomInventoryJpaRepository,
        private val rateJpaRepository: DailyRoomRateJpaRepository,
        private val issuedCouponJpaRepository: IssuedCouponJpaRepository,
        private val reservationRepository: ReservationRepository,
        private val databaseCleanUp: DatabaseCleanUp,
    ) {
        private val checkIn = LocalDate.of(2026, 6, 1)
        private val checkOut = LocalDate.of(2026, 6, 2)
        private val alice = LoginId("alice01")

        @AfterEach
        fun tearDown() {
            databaseCleanUp.truncateAllTables()
        }

        @DisplayName("같은 쿠폰으로 동시에 두 번 예약해도 한 건만 성공하고 재고도 한 번만 차감된다.")
        @Test
        fun duplicateCouponReservation_reflectedOnce() {
            val roomTypeId = seedRoomTypeWithStock(total = 5)
            val couponId = issueCoupon()

            val success = runConcurrent(2) { attempt { reservationFacade.reserve(command(roomTypeId, couponId)) } }

            assertThat(success).isEqualTo(1)
            assertThat(inventory(roomTypeId).reservedRooms).isEqualTo(1)
            assertThat(issuedCouponJpaRepository.findById(couponId).orElseThrow().status).isEqualTo(CouponStatus.USED)
        }

        @DisplayName("같은 예약을 동시에 여러 번 취소해도 재고는 한 번만 복원된다(과복원 없음).")
        @Test
        fun duplicateCancel_restoresOnce() {
            val roomTypeId = seedRoomTypeWithStock(total = 5)
            val propertyId = propertyIdOf(roomTypeId)
            val idA = reservationFacade.reserve(command(roomTypeId, couponId = null)).reservationId
            reservationFacade.reserve(reserveAs(LoginId("bob002"), propertyId, roomTypeId)) // 같은 날짜 다른 예약 → reserved=2
            assertThat(inventory(roomTypeId).reservedRooms).isEqualTo(2)

            val success = runConcurrent(20) { attempt { reservationFacade.cancel(alice, idA) } }

            assertThat(success).isEqualTo(1)
            assertThat(inventory(roomTypeId).reservedRooms).isEqualTo(1) // bob 의 1실만 남음(과복원 없음)
        }

        @DisplayName("이미 사용한 쿠폰으로 예약하면 CONFLICT 이고 재고 차감·예약 생성이 전체 롤백된다.")
        @Test
        fun usedCouponReservation_rollsBackEverything() {
            val roomTypeId = seedRoomTypeWithStock(total = 5)
            val couponId = issueCoupon()
            reservationFacade.reserve(command(roomTypeId, couponId)) // 쿠폰을 한 번 사용(성공)

            assertThatThrownBy { reservationFacade.reserve(command(roomTypeId, couponId)) }
                .isInstanceOf(CoreException::class.java)

            // 첫 예약 1건만 반영 — 두 번째 시도의 재고 차감은 쿠폰 실패로 전체 롤백되어 reserved 는 1 유지.
            assertThat(inventory(roomTypeId).reservedRooms).isEqualTo(1)
            assertThat(reservationRepository.findByUserId(alice, 0, 20)).hasSize(1)
        }

        private fun seedRoomTypeWithStock(total: Int): Long {
            val property = propertyRepository.save(
                PropertyModel.create(
                    name = PropertyName("스테이루프 호텔"),
                    category = PropertyCategory.HOTEL,
                    address = Address("seoul", "서울특별시 중구 세종대로 110"),
                    policy = PropertyPolicy.standard(),
                ),
            )
            val roomType = roomTypeRepository.save(
                RoomTypeModel.create(
                    propertyId = property.id,
                    name = "디럭스 더블",
                    guestCount = GuestCount(2, 4),
                    bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1)),
                ),
            )
            inventoryJpaRepository.save(DailyRoomInventoryModel(roomType.id, checkIn, totalRooms = total))
            rateJpaRepository.save(DailyRoomRateModel(roomType.id, checkIn, Money.of(100_000)))
            return roomType.id
        }

        private fun issueCoupon(): Long {
            val templateId = couponFacade.createTemplate(
                CreateCouponTemplateCommand("선착순", DiscountType.FIXED, 5_000, 100),
            ).templateId
            return couponFacade.issue(IssueCouponCommand(alice, templateId)).issuedCouponId
        }

        private fun command(roomTypeId: Long, couponId: Long?) =
            ReserveCommand(
                loginId = alice,
                propertyId = propertyIdOf(roomTypeId),
                roomTypeId = roomTypeId,
                checkIn = checkIn,
                checkOut = checkOut,
                guestCount = 2,
                guestName = "홍길동",
                guestPhoneNumber = "010-1234-5678",
                issuedCouponId = couponId,
            )

        private fun reserveAs(who: LoginId, propertyId: Long, roomTypeId: Long) =
            ReserveCommand(
                loginId = who,
                propertyId = propertyId,
                roomTypeId = roomTypeId,
                checkIn = checkIn,
                checkOut = checkOut,
                guestCount = 2,
                guestName = "김철수",
                guestPhoneNumber = "010-9999-8888",
                issuedCouponId = null,
            )

        private fun propertyIdOf(roomTypeId: Long): Long =
            roomTypeRepository.findById(roomTypeId)!!.propertyId

        private fun inventory(roomTypeId: Long): DailyRoomInventoryModel =
            inventoryJpaRepository.findById(DailyRoomInventoryId(roomTypeId, checkIn)).orElseThrow()

        private fun attempt(action: () -> Unit): Boolean =
            try {
                action()
                true
            } catch (e: CoreException) {
                false
            } catch (e: PessimisticLockingFailureException) {
                false
            }

        private fun runConcurrent(n: Int, task: () -> Boolean): Int {
            val successes = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(n)
            try {
                val start = CountDownLatch(1)
                val futures = (0 until n).map {
                    pool.submit {
                        start.await()
                        if (task()) successes.incrementAndGet()
                    }
                }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            return successes.get()
        }
    }
