package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `DailyRoomInventoryRepository` 와 분리되어 있고
 * `DailyRoomInventoryRepositoryImpl` 이 위임한다 (3-class 분리, `.github/instructions/repository.instructions.md`).
 *
 * `JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 *
 * 본 인터페이스는 `JpaRepository` *만* 상속하며 별도 메서드를 갖지 않는다 — 반-닫힌 구간 조회 등 도메인성 쿼리는
 * `DailyRoomInventoryRepositoryImpl` 안의 **QueryDSL** 로 표현 (문자열 JPQL `@Query` 미사용).
 */
interface DailyRoomInventoryJpaRepository : JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId>
