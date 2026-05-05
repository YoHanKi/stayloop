package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `PropertyRepository` 와 분리되어 있고 `PropertyRepositoryImpl` 이 위임한다
 * (3-class 분리, `.github/instructions/repository.instructions.md`).
 *
 * 본 인터페이스는 `JpaRepository<PropertyModel, Long>` *만* 상속하며 별도 메서드를 갖지 않는다 — 도시 검색 등
 * 도메인성 쿼리는 모두 `PropertyRepositoryImpl` 안의 **QueryDSL** 로 표현 (문자열 JPQL `@Query` 미사용).
 */
interface PropertyJpaRepository : JpaRepository<PropertyModel, Long>
