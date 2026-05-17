package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyImageModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `PropertyImageRepository` 와 분리, `PropertyImageRepositoryImpl` 이 위임한다
 * (3-class 분리, `.github/instructions/repository.instructions.md`).
 *
 * 도메인성 쿼리 (정렬 / 정렬 키 등) 는 `PropertyImageRepositoryImpl` 안의 **QueryDSL** 로 표현.
 */
interface PropertyImageJpaRepository : JpaRepository<PropertyImageModel, Long>
