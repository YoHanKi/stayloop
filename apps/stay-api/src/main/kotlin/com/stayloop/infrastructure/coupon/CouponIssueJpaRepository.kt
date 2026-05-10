package com.stayloop.infrastructure.coupon

import com.stayloop.domain.coupon.CouponIssueModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `CouponIssueRepository` 와 분리되어 있고
 * `CouponIssueRepositoryImpl` 이 위임한다 (3-class 분리).
 *
 * **boundary 차이**: 도메인 인터페이스는 `LoginId` 를 받지만 본 인터페이스는 `users.id` (Long) 를 받는다 —
 * `LoginId ↔ users.id` 변환은 `CouponIssueRepositoryImpl` 의 책임 (Wishlist 패턴 답습).
 *
 * 본 인터페이스는 `JpaRepository` *만* 상속하며 별도 메서드를 갖지 않는다 — `findByUserId` 등 도메인성 쿼리는
 * `CouponIssueRepositoryImpl` 안의 **QueryDSL** 로 표현.
 */
interface CouponIssueJpaRepository : JpaRepository<CouponIssueModel, Long>
