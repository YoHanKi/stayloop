package com.stayloop.domain.coupon

interface CouponTemplateRepository {
    fun findById(id: Long): CouponTemplateModel?

    fun save(template: CouponTemplateModel): CouponTemplateModel

    /**
     * `issued_count + 1 <= total_quantity` 인 경우에만 발급 수를 1 늘리고 **갱신된 행 수(0 또는 1)**를 돌려준다.
     * 0 행 = 소진 같은 해석은 호출하는 도메인 서비스가 한다(영속성은 사실만 반환).
     */
    fun increaseIssuedIfAvailable(templateId: Long): Int
}
