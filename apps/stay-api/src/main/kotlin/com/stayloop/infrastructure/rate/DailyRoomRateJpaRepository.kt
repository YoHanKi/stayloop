package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `DailyRoomRateRepository` 와 분리되어 있고
 * `DailyRoomRateRepositoryImpl` 이 위임한다 (3-class 분리, `.github/instructions/repository.instructions.md`).
 *
 * `JpaRepository<DailyRoomRateModel, DailyRoomRateId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 *
 * 본 인터페이스는 `JpaRepository` *만* 상속하며 별도 메서드를 갖지 않는다 — 반-닫힌 구간 조회 등 도메인성 쿼리는
 * `DailyRoomRateRepositoryImpl` 안의 **QueryDSL** 로 표현 (문자열 JPQL `@Query` 미사용).
 */
interface DailyRoomRateJpaRepository : JpaRepository<DailyRoomRateModel, DailyRoomRateId>
