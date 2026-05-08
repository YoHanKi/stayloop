package com.stayloop.infrastructure.coupon

import com.stayloop.domain.coupon.CouponTemplateModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `CouponTemplateRepository` 와 분리되어 있고
 * `CouponTemplateRepositoryImpl` 이 위임한다 (3-class 분리,
 * `.github/instructions/repository.instructions.md`).
 *
 * 본 인터페이스는 `JpaRepository` *만* 상속하며 별도 메서드를 갖지 않는다 — `findByCode` 등 도메인성 쿼리는
 * `CouponTemplateRepositoryImpl` 안의 **QueryDSL** 로 표현 (문자열 JPQL `@Query` 미사용,
 * `docs/plan/week4/decision.md` D-6).
 */
interface CouponTemplateJpaRepository : JpaRepository<CouponTemplateModel, Long>
