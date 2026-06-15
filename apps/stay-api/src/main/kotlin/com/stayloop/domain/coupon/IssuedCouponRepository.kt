package com.stayloop.domain.coupon

import com.stayloop.domain.user.value.LoginId
import java.time.LocalDateTime

interface IssuedCouponRepository {
    fun findById(id: Long): IssuedCouponModel?

    fun save(issued: IssuedCouponModel): IssuedCouponModel

    /** 사용자 발급 쿠폰을 최신 발급순으로 조회한다. */
    fun findByUser(userId: LoginId, page: Int, size: Int): List<IssuedCouponModel>

    /**
     * `status = AVAILABLE` 인 경우에만 USED 로 전이하고 **갱신된 행 수(0 또는 1)**를 돌려준다.
     * 0 행 = 이미 사용 같은 해석은 호출하는 도메인 서비스가 한다(영속성은 사실만 반환).
     */
    fun markUsedIfAvailable(issuedCouponId: Long, usedAt: LocalDateTime): Int
}
