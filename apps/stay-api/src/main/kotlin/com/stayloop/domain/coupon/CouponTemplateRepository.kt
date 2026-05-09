package com.stayloop.domain.coupon

import com.stayloop.domain.common.value.PageQuery

/**
 * `CouponTemplate` 의 도메인 Repository 인터페이스. (`docs/plan/week4.md` ① Phase A-3 / C-1)
 *
 * 구현체:
 * - 운영 — `infrastructure/coupon/CouponTemplateRepositoryImpl` (A-5 시점, QueryDSL 위임)
 * - 테스트 — `support/test/InMemoryCouponTemplateRepository` (운영과 동일 의미론)
 *
 * **`findAll` 정렬** — `id DESC` (최신 등록 순) 고정. `page.sort` 가 비어있지 않으면 BAD_REQUEST 거절
 * (다른 어드민 list 패턴 답습 — verify-code §16-A silent ignore 차단).
 *
 * **`deleteById` 는 hard delete** — 본 라운드는 soft delete 미도입. 발급 이력이 있는 템플릿 삭제는 Facade 가
 * `CouponIssueRepository.existsByTemplateId` 로 사전 가드 (Phase C-1 결정).
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

    /**
     * 다수 id 배치 조회. 입력 순서 보존하지 않으며 (호출자가 `associateBy { id }` 로 정렬),
     * 누락된 id 는 결과에서 빠진다 (호출자가 누락 정책 결정).
     *
     * `getMyCoupons` 의 N+1 회피용 — 페이지 크기만큼만 1회 IN 쿼리.
     */
    fun findAllByIds(ids: Collection<Long>): List<CouponTemplateModel>

    /**
     * 어드민 list 조회 — `id DESC` 고정 정렬. `page.sort` 비어있지 않으면 BAD_REQUEST.
     */
    fun findAll(page: PageQuery): List<CouponTemplateModel>

    /**
     * id 로 hard delete. 존재하지 않으면 noop (Spring Data 의미론 답습).
     */
    fun deleteById(id: Long)
}
