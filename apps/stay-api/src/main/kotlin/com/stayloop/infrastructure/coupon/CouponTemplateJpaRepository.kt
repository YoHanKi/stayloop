package com.stayloop.infrastructure.coupon

import com.stayloop.domain.coupon.CouponTemplateModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 쿼리는 QueryDSL(RepositoryImpl)로 둔다. */
interface CouponTemplateJpaRepository : JpaRepository<CouponTemplateModel, Long>
