package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository

/**
 * 운영 어댑터와 동치 의미론의 더블 — `increaseIssuedIfAvailable` 는 remaining > 0 일 때만 발급 수를 1 늘리고
 * 1 을, 아니면 0 을 돌려준다(조건부 원자 UPDATE 와 동치). id·issuedCount 는 reflection 으로 할당한다.
 */
class InMemoryCouponTemplateRepository : CouponTemplateRepository {
    private val store = LinkedHashMap<Long, CouponTemplateModel>()
    private var sequence = 0L

    override fun findById(id: Long): CouponTemplateModel? = store[id]

    override fun save(template: CouponTemplateModel): CouponTemplateModel {
        if (template.id == 0L) {
            assignId(template, ++sequence)
        }
        store[template.id] = template
        return template
    }

    override fun increaseIssuedIfAvailable(templateId: Long): Int {
        val template = store[templateId] ?: return 0
        if (template.remaining() <= 0) return 0
        setIssuedCount(template, template.issuedCount + 1)
        return 1
    }

    private fun assignId(entity: BaseEntity, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(entity, id)
    }

    private fun setIssuedCount(template: CouponTemplateModel, value: Int) {
        val field = CouponTemplateModel::class.java.getDeclaredField("issuedCount")
        field.isAccessible = true
        field.setInt(template, value)
    }
}
