package com.stayloop.domain.coupon

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.value.LoginId

/**
 * `CouponIssue` 의 도메인 Repository 인터페이스. (`docs/plan/week4.md` ① Phase A-4)
 *
 * 구현체:
 * - 운영 — `infrastructure/coupon/CouponIssueRepositoryImpl` (A-5 시점, QueryDSL 위임)
 * - 테스트 — `support/test/InMemoryCouponIssueRepository` (운영과 동일 의미론)
 *
 * **boundary 는 `LoginId`** — Facade / 도메인 서비스가 `LoginId` 를 들고 다닐 수 있도록 시그니처에 명시.
 * Issue 자체가 `userId: LoginId` 를 보존하므로 Wishlist 와 달리 BIGINT 변환은 필요 없다.
 *
 * **`findByUserId(userId, page)` 정렬** — `issuedAt DESC, id DESC` 고정 (최근 발급순 + tie-breaker).
 * `page.sort` 가 비어있지 않으면 BAD_REQUEST 거절 (Wishlist 와 동일 정책 — verify-code §16-A silent ignore 차단).
 *
 * 본 라운드 메서드 범위는 *대고객 발급 / 사용 / 목록 조회* 까지. *어드민 발급 이력 페이지네이션* / *코드 입력
 * 발급 (`findByCodeAndUserId`)* 은 후속 phase 합류 시점에 추가한다 (YAGNI).
 */
interface CouponIssueRepository {
    /**
     * 신규 발급 또는 사용 상태 갱신 (use → USED).
     */
    fun save(issue: CouponIssueModel): CouponIssueModel

    /**
     * `id` 단건 조회. 없으면 null.
     */
    fun findById(id: Long): CouponIssueModel?

    /**
     * 사용자의 발급 이력을 페이지 단위로 조회 — 정렬은 `issuedAt DESC, id DESC` 고정.
     * `page.sort` 가 비어있지 않으면 BAD_REQUEST.
     */
    fun findByUserId(userId: LoginId, page: PageQuery): List<CouponIssueModel>
}
