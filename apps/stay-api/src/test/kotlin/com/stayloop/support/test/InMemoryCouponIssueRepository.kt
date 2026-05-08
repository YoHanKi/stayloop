package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 테스트용 InMemory `CouponIssueRepository`. 운영 RepositoryImpl 과 **동일 의미론** —
 * - `findByUserId` 정렬 고정 `issuedAt DESC, id DESC`
 * - `page.sort` 비어있지 않으면 BAD_REQUEST (verify-code §16-A silent ignore 차단)
 *
 * id 자동 할당은 reflection — 다른 InMemory 더블 패턴 답습 (테스트 fake 한정 트레이드오프).
 */
class InMemoryCouponIssueRepository : CouponIssueRepository {
    private val store = mutableMapOf<Long, CouponIssueModel>()
    private var sequence = 0L

    override fun save(issue: CouponIssueModel): CouponIssueModel {
        if (issue.id == 0L) {
            assignId(issue, ++sequence)
        }
        store[issue.id] = issue
        return issue
    }

    override fun findById(id: Long): CouponIssueModel? = store[id]

    override fun findByUserId(userId: LoginId, page: PageQuery): List<CouponIssueModel> {
        if (page.sort.isNotEmpty()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "쿠폰 목록 조회는 정렬 입력을 받지 않습니다 (issuedAt DESC 고정).",
            )
        }
        return store.values
            .filter { it.userId == userId }
            .sortedWith(
                compareByDescending<CouponIssueModel> { it.issuedAt }
                    .thenByDescending { it.id },
            )
            .drop(page.offset)
            .take(page.limit)
    }

    private fun assignId(issue: CouponIssueModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(issue, id)
    }
}
