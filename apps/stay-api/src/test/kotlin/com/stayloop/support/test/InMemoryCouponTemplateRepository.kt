package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository

/**
 * 테스트용 InMemory `CouponTemplateRepository`. 운영 RepositoryImpl 과 **동일 의미론** —
 * - `findByCode(code)` 는 UNIQUE 가정 (단건 또는 null)
 *
 * id 자동 할당은 `BaseEntity::class.java.getDeclaredField("id")` reflection — 다른 InMemory 더블 패턴 답습.
 * 테스트 fake 한정으로 의식적으로 받아들인 트레이드오프 (`.github/copilot-instructions.md`).
 */
class InMemoryCouponTemplateRepository : CouponTemplateRepository {
    private val store = mutableMapOf<Long, CouponTemplateModel>()
    private var sequence = 0L

    override fun save(template: CouponTemplateModel): CouponTemplateModel {
        if (template.id == 0L) {
            assignId(template, ++sequence)
        }
        store[template.id] = template
        return template
    }

    override fun findById(id: Long): CouponTemplateModel? = store[id]

    override fun findByCode(code: String): CouponTemplateModel? =
        store.values.firstOrNull { it.code == code }

    private fun assignId(template: CouponTemplateModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(template, id)
    }
}
