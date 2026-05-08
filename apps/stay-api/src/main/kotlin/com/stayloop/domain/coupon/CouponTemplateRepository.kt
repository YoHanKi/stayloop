package com.stayloop.domain.coupon

/**
 * `CouponTemplate` 의 도메인 Repository 인터페이스. (`docs/plan/week4.md` ① Phase A-3)
 *
 * 구현체:
 * - 운영 — `infrastructure/coupon/CouponTemplateRepositoryImpl` (A-5 시점, QueryDSL 위임)
 * - 테스트 — `support/test/InMemoryCouponTemplateRepository` (운영과 동일 의미론)
 *
 * 본 라운드 메서드 범위는 *대고객 발급 / 어드민 단건 조회* 까지. 어드민 페이지네이션 / hard delete 등은
 * Phase C 합류 시점에 추가한다 (YAGNI).
 */
interface CouponTemplateRepository {
    /**
     * 신규 등록 또는 갱신.
     */
    fun save(template: CouponTemplateModel): CouponTemplateModel

    /**
     * `id` 단건 조회. 없으면 null.
     */
    fun findById(id: Long): CouponTemplateModel?

    /**
     * `code` 단건 조회 — UNIQUE 제약이 1행 보장. 없으면 null.
     */
    fun findByCode(code: String): CouponTemplateModel?
}
