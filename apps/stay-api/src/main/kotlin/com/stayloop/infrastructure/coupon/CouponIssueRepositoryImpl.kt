package com.stayloop.infrastructure.coupon

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.coupon.QCouponIssueModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Component

/**
 * 도메인 `CouponIssueRepository` 의 인프라 어댑터. JpaRepository 위임 + QueryDSL + `LoginId ↔ users.id` 변환.
 *
 * **변환 책임**: 도메인 boundary 의 `LoginId` 는 본 클래스에서만 `users.id` (Long) 로 풀린다 —
 * 도메인 / Facade 호출자는 BIGINT 를 모른다 (`Wishlist` 패턴 답습).
 *
 * 누락 사용자 정책:
 * - `findByUserId` — silent empty (저장된 행이 없는 것과 같다)
 *
 * **`findByUserId` 는 QueryDSL** — `issuedAt DESC, id DESC` 고정 정렬 (`page.sort` 비어있지 않으면
 * BAD_REQUEST 거절). 문자열 JPQL `@Query` 우회로 type-safe 경로 + 정렬 결정성
 * (verify-code §4 / §17, `docs/plan/week4/decision.md` D-6).
 */
@Component
class CouponIssueRepositoryImpl(
    private val jpa: CouponIssueJpaRepository,
    private val users: UserRepository,
    private val queryFactory: JPAQueryFactory,
) : CouponIssueRepository {

    override fun save(issue: CouponIssueModel): CouponIssueModel = jpa.save(issue)

    override fun findById(id: Long): CouponIssueModel? = jpa.findById(id).orElse(null)

    override fun findByUserId(userId: LoginId, page: PageQuery): List<CouponIssueModel> {
        if (page.sort.isNotEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, SORT_NOT_SUPPORTED_MESSAGE)
        }
        val resolved = users.findByLoginId(userId)?.id ?: return emptyList()
        val i = QCouponIssueModel.couponIssueModel
        return queryFactory
            .selectFrom(i)
            .where(i.userId.eq(resolved))
            .orderBy(i.issuedAt.desc(), i.id.desc())
            .offset(page.page.toLong() * page.size.toLong())
            .limit(page.size.toLong())
            .fetch()
    }

    companion object {
        private const val SORT_NOT_SUPPORTED_MESSAGE =
            "쿠폰 목록은 issuedAt DESC 로 고정 정렬되며, 사용자 정의 정렬을 지원하지 않습니다."
    }
}
