package com.stayloop.application.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.infrastructure.cache.CacheStore
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDateTime

/**
 * 찜 토글 (시퀀스 4) Facade. (`docs/design/02-sequence-diagram.md §4`, `docs/plan/week2-3.md §⑧ Phase B`)
 *
 * **트랜잭션 정책** — `wish` / `unwish` 는 *읽기-쓰기* (`@Transactional`), `getMyWishes` 는
 * `readOnly = true`. wishCount 캐시 갱신은 같은 TX 안에서 일어나야 한다.
 *
 * **멱등 처리** (AC-6):
 * - 이미 찜된 Property 에 `wish` 재호출 → noop (`wished = true` 반환, wishCount 불변)
 * - 찜되지 않은 Property 에 `unwish` 호출 → noop (`wished = false` 반환, wishCount 불변)
 * - 도메인 모델 `Property.decrementWishCount` 가 `wishCount > 0` 가드를 가지므로 멱등 흐름이 0 미만 진입 차단
 *
 * **본인 자원 인가** (AC-7) — `getMyWishes(loginId, targetUserId)` 가 *path 의 userId* 와 *헤더의 loginId* 를
 * 비교해 일치하지 않으면 FORBIDDEN. 본 Facade 는 `LoginId` 만 들고 다니므로 path/header 모두 LoginId 로
 * 비교한다 (실 사용자식별은 `users.id` BIGINT 가 아니라 LoginId 값).
 *
 * **wishCount 동시 증감 정합성 — atomic UPDATE 채택** (`docs/plan/week4.md` ③ Phase C-2, decision.md D-1 #4)
 *
 * 본 Facade 는 `propertyRepository.atomicIncrementWishCount(propertyId)` /
 * `atomicDecrementWishCount(propertyId)` 를 호출한다. 운영 SQL 한 줄로 race window 0 — read-modify-write
 * (`findById → incrementWishCount → save`) 의 lost update 문제를 *DB-side atomic* 으로 차단.
 *
 * **`Property.incrementWishCount` / `decrementWishCount` 도메인 메서드는 *마지막 방어선* 으로 유지**:
 * - 운영 코드는 atomic UPDATE 로 우회 — 도메인 메서드를 호출하지 않는다.
 * - 그러나 도메인 단위 테스트 (`PropertyModelTest`) 는 도메인 가드 (`wishCount <= 0` CONFLICT) 를 검증.
 * - InMemory 더블 (`InMemoryPropertyRepository`) 의 atomic 메서드는 *내부적으로* 도메인 메서드를 호출 —
 *   영속성 단위에서 가드가 그대로 살아있음 (verify-code §19-B 문서 ↔ 가드 정합).
 * - 미래 운영 코드가 atomic 우회 없이 read-modify-write 로 회귀해도 도메인 메서드의 가드가 *최후의 방어선*.
 *
 * **응답 `wishCount` 의 의미** — atomic 호출 후 *그 호출의 +1 박제* 만 응답한다 (`property.wishCount + 1`).
 * 동시 다른 thread 의 증감은 응답에 반영되지 않으나, *DB 정합성* 은 atomic 으로 보장. UX 측면에서 사용자는
 * "내 wish 가 적용되었다 + 카운트가 +1 되었다" 를 알면 충분 — 정확한 글로벌 카운트는 별도 조회가 본질.
 *
 * **Read-then-Write SELECT 중복 — 의식적 trade-off (verify-code §17)**
 *
 * `wish` 흐름은 `existsBy` (1+1 SELECT) → `save` (1 SELECT + INSERT) 로 사용자 행 SELECT 가 2회 발생한다
 * (`WishlistRepositoryImpl` 이 `LoginId → users.id` 변환을 위해 `UserRepository.findByLoginId` 를 두 번 호출).
 * `unwish` 도 `existsBy` → `deleteBy` 로 동일.
 *
 * 단일 쿼리로 통합하려면 두 가지 길이 있다:
 * 1. `WishlistRepository.save` 시그니처에 *이미 변환된 `users.id`* 를 주입 — 도메인 boundary (LoginId 캡슐화) 훼손.
 *    Facade 가 BIGINT 를 들고 다니게 되어 `feature/wishlist` PR #6 의 결정과 충돌.
 * 2. JPA L2 cache 또는 transaction-scoped 사용자 캐시 도입 — 4주차 캐시 영역.
 *
 * **본 라운드 결정**: 단순한 read-modify-write 를 유지하고 SELECT 중복은 4주차 동시성/캐시 라운드에서
 * 함께 다룬다. 단일 사용자 단발 토글 시나리오의 운영 부하는 낮으며, 4주차에 wishCount 동시 증감 정합성
 * (`Property.wishCount` race window) 과 같이 보는 것이 합당.
 */
@Service
class WishlistFacade(
    private val wishlistRepository: WishlistRepository,
    private val propertyRepository: PropertyRepository,
    private val cacheStore: CacheStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DETAIL_KEY_PREFIX = "property:detail:"
    }

    /**
     * `property:detail:{id}` cache 를 *TX commit 후* evict.
     *
     * **TX 안 evict = race window (Loop 7 §고민 5)**: evict 후 DB rollback 시 cache 가 *없는 상태*, DB 는
     * *옛 상태* → 다음 read 가 옛 상태로 cache 재진입 → 옛 wishCount 박제. **반드시 afterCommit** 에서만
     * 호출.
     *
     * **TX 가 없으면 즉시 evict** — 단위 테스트 / 비TX 호출 호환. 약속 자체는 "eventually evict" 라
     * TX 가 없는 경로에서는 즉시가 *그 약속의 단순 경로*.
     */
    private fun evictDetailCacheAfterCommit(propertyId: Long) {
        val key = DETAIL_KEY_PREFIX + propertyId
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        cacheStore.evict(key)
                    }
                },
            )
        } else {
            cacheStore.evict(key)
        }
    }

    /**
     * 숙소 찜 등록. 이미 찜된 경우 noop (멱등, AC-6). wishCount 갱신은 *atomic UPDATE* (Phase C-2).
     */
    @Transactional
    fun wish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (wishlistRepository.existsBy(loginId, propertyId)) {
            return WishlistToggleInfo(propertyId = propertyId, wished = true, wishCount = property.wishCount)
        }
        // atomic 호출 *이전* 의 wishCount 를 박제 — 운영 (JPA `update().execute()`) 은 entity manager 를 우회해
        // 로컬 entity 가 stale 로 남고, InMemory 는 도메인 메서드를 통해 같은 인스턴스를 mutate. 두 의미를
        // *동치* 로 만들기 위해 Facade 가 "atomic 호출 전 값 + 1" 을 응답으로 약속 (verify-code §19-A).
        val countBefore = property.wishCount
        wishlistRepository.save(loginId, propertyId, LocalDateTime.now(clock))
        propertyRepository.atomicIncrementWishCount(propertyId)
        // detail cache 의 wishCount 가 stale 이 되었으므로 TX commit 후 evict (PR4 A-3).
        evictDetailCacheAfterCommit(propertyId)
        return WishlistToggleInfo(propertyId = propertyId, wished = true, wishCount = countBefore + 1)
    }

    /**
     * 숙소 찜 취소. 찜되지 않은 경우 noop (멱등, AC-6). wishCount 갱신은 *atomic UPDATE* (Phase C-2).
     */
    @Transactional
    fun unwish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (!wishlistRepository.existsBy(loginId, propertyId)) {
            return WishlistToggleInfo(propertyId = propertyId, wished = false, wishCount = property.wishCount)
        }
        val countBefore = property.wishCount
        wishlistRepository.deleteBy(loginId, propertyId)
        // atomic UPDATE — `WHERE wish_count > 0` 가드로 음수 진입 차단. affected = 0 시 응답은 *atomic 호출 전*
        // 값 유지. *영향 race 시나리오 두 축* (DB 정합성은 atomic 이 양쪽 모두 보장):
        // (a) 다른 thread 가 *먼저 0 으로 만든* 케이스 — 본 unwish 의 atomic 이 noop, countBefore 도 stale
        //     (이미 0). 응답 wishCount=0 정합.
        // (b) 본 unwish 가 진행되는 동안 *다른 thread 가 wish 로 +1* 한 케이스 — REPEATABLE_READ snapshot 으로
        //     countBefore 가 stale 일 수 있음. 응답 wishCount 가 *본 thread 가 본 값* 이고 *글로벌 카운트* 는
        //     아님 — 사용자 UX 측면에서는 "내 unwish 가 적용됐다 + 카운트가 -1 됐다" 만 알면 충분.
        val affected = propertyRepository.atomicDecrementWishCount(propertyId)
        val responseCount = if (affected == 1) (countBefore - 1).coerceAtLeast(0) else countBefore
        // 실제 감소 발생 시에만 evict — affected = 0 (이미 0) 면 detail cache 의 wishCount 도 0 이라 stale 아님.
        if (affected == 1) {
            evictDetailCacheAfterCommit(propertyId)
        }
        return WishlistToggleInfo(propertyId = propertyId, wished = false, wishCount = responseCount)
    }

    /**
     * 사용자의 찜 목록 조회. 본인 자원 인가 — path 의 `targetUserId` 와 헤더의 `loginId` 가 다르면 FORBIDDEN
     * (AC-7). 정렬은 `wishedAt DESC` *고정* — `page.sort` 가 비어있지 않으면 Facade 진입점에서
     * `BAD_REQUEST` 로 거절한다 (verify-code §16-A — silent ignore 금지). Repository 단에도
     * 같은 가드가 있으나(`WishlistRepository.findByUserId` 계약 / `WishlistRepositoryImpl` /
     * `InMemoryWishlistRepository`), Facade 호출 계약을 *진입점에서* 명시해 두어야 다른 호출자가
     * 정렬 인자를 채워도 조용히 무시되지 않는다 (verify-code §19-B — 문서-동작 정합).
     *
     * **누락 Property 정책 (verify-code §8 / §19-B)**: wishlist 행은 존재하지만 `findAllByIds` 결과에
     * 해당 Property 가 없으면 (소프트 삭제 / 데이터 정합 깨짐 / 삭제 race) `INTERNAL_ERROR` 로
     * *명시적으로 실패* — `mapNotNull` 로 조용히 누락하면 운영에서 원인 파악이 어렵다. 누락된
     * propertyId 는 서버 로그(loginId 제외, propertyId 만)에 남겨 추적성을 확보하고, 응답 메시지는
     * 일반화 (verify-code §12 — 식별자 노출 금지).
     */
    @Transactional(readOnly = true)
    fun getMyWishes(loginId: LoginId, targetUserId: LoginId, page: PageQuery): List<WishlistItemInfo> {
        if (loginId != targetUserId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 찜 목록만 조회할 수 있습니다.")
        }
        if (page.sort.isNotEmpty()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "Wishlist 목록은 최근 찜 순으로 고정 정렬되며, 사용자 정의 정렬을 지원하지 않습니다.",
            )
        }
        val wishes = wishlistRepository.findByUserId(loginId, page)
        if (wishes.isEmpty()) return emptyList()
        // 한 번의 batch 조회로 N+1 회피 — Property 조회는 페이지 size 만큼만 1회.
        val properties = propertyRepository.findAllByIds(wishes.map { it.propertyId }).associateBy { it.id }
        val missing = wishes.map { it.propertyId }.filter { it !in properties }
        if (missing.isNotEmpty()) {
            // 식별자(LoginId) 는 로그에서도 마스킹 — propertyId 만 남겨 데이터 정합 추적용.
            log.warn("Wishlist 조회 — 참조 Property 누락. missingPropertyIds={}", missing)
            throw CoreException(
                ErrorType.INTERNAL_ERROR,
                "찜 목록 조회 중 일관성이 깨진 데이터를 발견했습니다.",
            )
        }
        return wishes.map { wish ->
            val property = properties.getValue(wish.propertyId)
            WishlistItemInfo.of(wish, property)
        }
    }
}
