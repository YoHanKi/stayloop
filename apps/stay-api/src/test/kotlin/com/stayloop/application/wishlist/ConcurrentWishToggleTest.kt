package com.stayloop.application.wishlist

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.stayloop.domain.user.value.Name as UserName

/**
 * **동시성 E2E** — 같은 사용자 + 같은 숙소 × 10 스레드 wish/unwish 무작위 토글 시나리오.
 * (`docs/plan/week4.md` ③ Phase C-3, week4-quests 동시성 ① "동일 숙소 찜/찜취소 정합성")
 *
 * **검증 의도**:
 * - **wishlist row 정합성**: 자연키 PK `(user_id, property_id)` UNIQUE → 종료 후 row 수 ∈ {0, 1}.
 * - **`properties.wish_count` 정합성**: atomic UPDATE (`+1` / `-1 WHERE wish_count > 0`) 로 음수 진입 0,
 *   종료 후 wish_count ∈ {0, 1}.
 * - **wishlist row 존재 ↔ wish_count = 1 의 *대응* 성립** — race 패턴이 *글로벌 정합성* 을 깨지 않음. row 가
 *   있는데 wish_count=0 또는 row 가 없는데 wish_count=1 이면 *어디선가 atomic UPDATE 가 빠졌다는 신호*
 *   (wishlist 행 INSERT/DELETE 와 atomic 호출의 1:1 짝).
 *
 * **시나리오 설계** — 10 스레드가 *같은 사용자 / 같은 숙소* 에 wish 또는 unwish 를 무작위 (deterministic seed)
 * 호출. `existsBy` 의 race 와 wishlist UNIQUE 제약, 그리고 atomic 증감의 race window 0 정합성을 동시에 검증.
 *
 * **예외 흡수 정책**:
 * - `wish` 호출이 wishlist UNIQUE 위반을 만나면 raw `DataIntegrityViolationException` 이 흘러나올 수 있음 —
 *   본 라운드 Facade 는 *변환하지 않음* (Phase C scope: wishCount atomic 만). E2E 는 `others` 카운터로
 *   raw 예외를 *허용* 하면서 *최종 정합성* 만 검증 (verify-tests §4-A 명세성 정합 — DisplayName 이
 *   "최종 row/wish_count 정합" 만 약속).
 *
 * **InMemory 더블 사용 금지** — atomic UPDATE / UNIQUE 제약 / TX rollback 의미론은 InnoDB 만의 동작
 * (verify-code R9). 본 테스트는 Testcontainers MySQL 8.0 으로만 의미가 있다.
 *
 * **격리** — `@AfterEach` 의 `truncateAllTables()` (`docs/plan/week4.md` Q7).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class ConcurrentWishToggleTest {
    @Autowired
    private lateinit var sut: WishlistFacade

    @Autowired
    private lateinit var properties: PropertyRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var wishes: WishlistRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    private val loginId = LoginId("alen01")

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName(
        "같은 사용자 / 같은 숙소 × 10 스레드 wish/unwish 무작위 토글 시 — " +
            "wishlist row 수 ∈ {0,1} + properties.wish_count ∈ {0,1} + 둘의 대응 성립.",
    )
    @Test
    fun shouldKeepWishlistAndWishCountConsistent_whenTenThreadsToggleConcurrently() {
        // given — 사용자 1명, 숙소 1개 (wish_count = 0, wishlist 없음)
        seedUser()
        val property = seedProperty()

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val wishCalls = AtomicInteger(0)
        val unwishCalls = AtomicInteger(0)
        val others = AtomicInteger(0)
        val failures: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())

        // when — 10 스레드가 deterministic random 으로 wish 또는 unwish 호출. 같은 seed 로 재현 가능
        val rand = java.util.Random(42L)
        val choices = (0 until threadCount).map { rand.nextBoolean() } // true = wish, false = unwish

        choices.forEachIndexed { i, doWish ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    if (doWish) {
                        sut.wish(loginId, property.id)
                        wishCalls.incrementAndGet()
                    } else {
                        sut.unwish(loginId, property.id)
                        unwishCalls.incrementAndGet()
                    }
                } catch (e: Exception) {
                    // wishlist UNIQUE 위반이 raw `DataIntegrityViolationException` 으로 흘러나올 수 있음.
                    // Phase C scope 외 — 최종 정합성만 검증 (KDoc 정책 박제).
                    others.incrementAndGet()
                    failures.add(e)
                    System.err.println("worker $i (${if (doWish) "wish" else "unwish"}) raw: ${e::class.simpleName}: ${e.message}")
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "동시 wish/unwish 흐름이 30초 안에 완료되지 않았습니다." }
        executor.shutdown()

        // then — 최종 정합성 검증
        val finalProperty = properties.findById(property.id)
            ?: error("property 가 누락되었습니다 (id=${property.id})")
        val wishlistRowExists = wishes.existsBy(loginId, property.id)
        val sample = failures.take(3).joinToString("\n  ") { "${it::class.simpleName}: ${it.message}" }

        // (1) wish_count 는 음수 진입 X — atomic decrement 의 `WHERE wish_count > 0` 가드 검증
        assertThat(finalProperty.wishCount)
            .withFailMessage(
                "wish_count 가 0 미만이거나 1 초과: %d. wish=%d, unwish=%d, others=%d. sample:\n  %s",
                finalProperty.wishCount,
                wishCalls.get(),
                unwishCalls.get(),
                others.get(),
                sample,
            )
            .isBetween(0, 1)

        // (2) wishlist row 수는 자연키 PK 로 자연 보장 ∈ {0, 1} — existsBy 결과로 확인
        // (3) wishlist row 존재 ↔ wish_count = 1 의 *대응* 성립 (atomic UPDATE 가 wishlist 변경과 1:1 짝)
        assertThat(if (wishlistRowExists) 1 else 0)
            .withFailMessage(
                "wishlist row 존재(%s) 와 wish_count(%d) 의 대응 깨짐. wish=%d, unwish=%d, others=%d. sample:\n  %s",
                wishlistRowExists,
                finalProperty.wishCount,
                wishCalls.get(),
                unwishCalls.get(),
                others.get(),
                sample,
            )
            .isEqualTo(finalProperty.wishCount)
    }

    private fun seedUser(): UserModel = users.save(
        UserModel.create(
            loginId = loginId,
            rawPassword = "Abcd1234!",
            name = UserName("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("alen01@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = passwordEncoder,
        ),
    )

    private fun seedProperty(): PropertyModel = properties.save(
        PropertyModel.create(
            name = Name("강남호텔"),
            category = PropertyCategory.HOTEL,
            description = "동시성 E2E 시드",
            address = Address(city = "SEOUL", fullAddress = "SEOUL 어딘가 123"),
            amenities = Amenities.EMPTY,
            policy = PropertyPolicy(
                checkInTime = LocalTime.of(15, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            ),
        ),
    )
}
