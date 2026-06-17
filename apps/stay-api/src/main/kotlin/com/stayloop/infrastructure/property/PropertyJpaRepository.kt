package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 조회 쿼리는 QueryDSL(RepositoryImpl)로 둔다. */
interface PropertyJpaRepository : JpaRepository<PropertyModel, Long>
