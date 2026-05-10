package com.stayloop.support.test

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * `InMemoryPropertyRepository` 의 *atomic 증감* 의미론 단위 테스트. (`docs/plan/week4.md` ③ Phase C-1)
 *
 * 운영 RepositoryImpl 의 SQL 의미론 (`UPDATE ... WHERE id = ?` / `WHERE id = ? AND wish_count > 0`) 과 동일
 * 결과를 InMemory 더블이 재현하는지 검증 (verify-code §19-A 운영-테스트 동치). 동시성 정합성은 `@Modifying`
 * SQL / Hibernate 락의 영역이라 본 단위 테스트로 입증 X — `application/wishlist/ConcurrentWishToggleTest`
 * 가 Testcontainers MySQL 로 검증 (verify-code R9 정합).
 */
class InMemoryPropertyRepositoryAtomicTest {
    private val repository = InMemoryPropertyRepository()

    @DisplayName("atomicIncrementWishCount — 존재하는 propertyId 면 wishCount 가 1 증가하고 affected = 1.")
    @Test
    fun shouldIncrementAndReturnOne_whenPropertyExists() {
        val saved = repository.save(newProperty())

        val affected = repository.atomicIncrementWishCount(saved.id)

        assertThat(affected).isEqualTo(1)
        assertThat(repository.findById(saved.id)?.wishCount).isEqualTo(1)
    }

    @DisplayName("atomicIncrementWishCount — 존재하지 않는 propertyId 면 affected = 0 (멱등 noop).")
    @Test
    fun shouldReturnZero_whenPropertyMissing() {
        val affected = repository.atomicIncrementWishCount(999L)

        assertThat(affected).isZero()
    }

    @DisplayName("atomicDecrementWishCount — wishCount > 0 이면 1 감소하고 affected = 1.")
    @Test
    fun shouldDecrementAndReturnOne_whenWishCountPositive() {
        val saved = repository.save(newProperty())
        repository.atomicIncrementWishCount(saved.id) // wishCount = 1

        val affected = repository.atomicDecrementWishCount(saved.id)

        assertThat(affected).isEqualTo(1)
        assertThat(repository.findById(saved.id)?.wishCount).isZero()
    }

    @DisplayName("atomicDecrementWishCount — wishCount = 0 이면 affected = 0 (음수 진입 차단, 멱등 noop).")
    @Test
    fun shouldReturnZero_whenWishCountZero() {
        val saved = repository.save(newProperty())

        val affected = repository.atomicDecrementWishCount(saved.id)

        assertThat(affected).isZero()
        assertThat(repository.findById(saved.id)?.wishCount).isZero()
    }

    @DisplayName("atomicDecrementWishCount — 존재하지 않는 propertyId 면 affected = 0.")
    @Test
    fun shouldReturnZero_whenPropertyMissing_decrement() {
        val affected = repository.atomicDecrementWishCount(999L)

        assertThat(affected).isZero()
    }

    private fun newProperty(): PropertyModel = PropertyModel.create(
        name = Name("테스트호텔"),
        category = PropertyCategory.HOTEL,
        description = "단위 테스트용",
        address = Address(city = "SEOUL", fullAddress = "SEOUL 어딘가 123"),
        amenities = Amenities.EMPTY,
        policy = PropertyPolicy(
            checkInTime = LocalTime.of(15, 0),
            checkOutTime = LocalTime.of(11, 0),
            cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
        ),
    )
}
