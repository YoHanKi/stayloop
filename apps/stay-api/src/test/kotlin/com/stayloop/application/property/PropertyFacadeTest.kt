package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.PropertySortKey
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.reservation.ReservationPriceCalculator
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryDailyRoomInventoryRepository
import com.stayloop.support.test.InMemoryDailyRoomRateRepository
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PropertyFacadeTest {
    private lateinit var propertyRepository: InMemoryPropertyRepository
    private lateinit var roomTypeRepository: InMemoryRoomTypeRepository
    private lateinit var inventoryRepository: InMemoryDailyRoomInventoryRepository
    private lateinit var rateRepository: InMemoryDailyRoomRateRepository
    private lateinit var sut: PropertyFacade

    private val checkIn = LocalDate.of(2026, 6, 1)
    private val checkOut = LocalDate.of(2026, 6, 3)

    @BeforeEach
    fun setUp() {
        propertyRepository = InMemoryPropertyRepository()
        roomTypeRepository = InMemoryRoomTypeRepository()
        inventoryRepository = InMemoryDailyRoomInventoryRepository()
        rateRepository = InMemoryDailyRoomRateRepository()
        sut = PropertyFacade(
            propertyRepository,
            roomTypeRepository,
            inventoryRepository,
            rateRepository,
            ReservationPriceCalculator(),
        )
    }

    private fun seedProperty(city: String = "seoul"): PropertyModel =
        propertyRepository.save(
            PropertyModel.create(
                name = PropertyName("스테이루프 호텔"),
                category = PropertyCategory.HOTEL,
                address = Address(city, "서울특별시 중구 세종대로 110"),
                policy = PropertyPolicy.standard(),
            ),
        )

    private fun seedRoomType(propertyId: Long, maxGuests: Int = 4): RoomTypeModel =
        roomTypeRepository.save(
            RoomTypeModel.create(
                propertyId = propertyId,
                name = "디럭스 더블",
                guestCount = GuestCount(2, maxGuests),
                bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1)),
            ),
        )

    private fun seedInventoryAndRate(roomTypeId: Long, available: Boolean = true) {
        inventoryRepository.saveAll(
            listOf(
                DailyRoomInventoryModel(roomTypeId, checkIn, totalRooms = 2, reservedRooms = if (available) 0 else 2),
                DailyRoomInventoryModel(roomTypeId, checkIn.plusDays(1), totalRooms = 2, reservedRooms = 0),
            ),
        )
        rateRepository.saveAll(
            listOf(
                DailyRoomRateModel(roomTypeId, checkIn, Money.of(100_000)),
                DailyRoomRateModel(roomTypeId, checkIn.plusDays(1), Money.of(120_000)),
            ),
        )
    }

    private fun criteria(guestCount: Int = 2, sortKey: PropertySortKey = PropertySortKey.RECOMMENDED) =
        PropertySearchCriteria(city = "seoul", checkIn = checkIn, checkOut = checkOut, guestCount = guestCount, sortKey = sortKey)

    @DisplayName("검색은 가용 객실이 있는 숙소를 최저 기간 합산가·1박 평균가와 함께 반환한다(AC-1).")
    @Test
    fun shouldReturnPropertiesWithAvailableRoomsAndLowestTotalPrice() {
        val property = seedProperty()
        seedInventoryAndRate(seedRoomType(property.id).id)

        val result = sut.search(criteria())

        assertThat(result).hasSize(1)
        assertThat(result[0].lowestTotalPrice).isEqualByComparingTo(BigDecimal("220000"))
        assertThat(result[0].averageNightlyPrice).isEqualByComparingTo(BigDecimal("110000.00"))
    }

    @DisplayName("재고가 누락되거나 가용이 0 인 객실만 있는 숙소는 검색에서 제외된다(AC-2).")
    @Test
    fun shouldExcludeRoomTypeWithMissingInventoryOrZeroAvailability() {
        val property = seedProperty()
        seedInventoryAndRate(seedRoomType(property.id).id, available = false)

        assertThat(sut.search(criteria())).isEmpty()
    }

    @DisplayName("요청 인원을 수용하지 못하는 객실만 있는 숙소는 검색에서 제외된다(AC-5).")
    @Test
    fun shouldExcludeRoomTypeWithGuestCountOverMax() {
        val property = seedProperty()
        seedInventoryAndRate(seedRoomType(property.id, maxGuests = 2).id)

        assertThat(sut.search(criteria(guestCount = 4))).isEmpty()
    }

    @DisplayName("추천순 외 정렬은 명시적으로 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldRejectUnsupportedSort() {
        assertThatThrownBy { sut.search(criteria(sortKey = PropertySortKey.PRICE_ASC)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("상세 조회는 숙소 정적 정보와 객실 타입 목록을 반환하고, 없으면 NOT_FOUND.")
    @Test
    fun shouldReturnDetailOrNotFound() {
        val property = seedProperty()
        seedRoomType(property.id)

        val detail = sut.getDetail(property.id)
        assertThat(detail.name).isEqualTo("스테이루프 호텔")
        assertThat(detail.roomTypes).hasSize(1)

        assertThatThrownBy { sut.getDetail(999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("가용 객실 조회는 기간·인원에 맞는 객실의 합산가를 반환한다.")
    @Test
    fun shouldReturnAvailableRooms() {
        val property = seedProperty()
        seedInventoryAndRate(seedRoomType(property.id).id)

        val rooms = sut.getAvailableRooms(RoomAvailabilityQuery(property.id, checkIn, checkOut, guestCount = 2))

        assertThat(rooms).hasSize(1)
        assertThat(rooms[0].totalPrice).isEqualByComparingTo(BigDecimal("220000"))
        assertThat(rooms[0].availableRooms).isEqualTo(2)
    }
}
