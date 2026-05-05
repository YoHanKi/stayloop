package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA 인터페이스. 도메인 `WishlistRepository` 와 분리되어 있고
 * `WishlistRepositoryImpl` 이 위임한다 (3-class 분리, `.github/instructions/repository.instructions.md`).
 *
 * `JpaRepository<WishlistModel, WishlistId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 *
 * **boundary 차이**: 도메인 인터페이스는 `LoginId` 를 받지만 본 인터페이스는 `users.id` (Long) 를 받는다 —
 * `LoginId ↔ users.id` 변환은 `WishlistRepositoryImpl` 의 책임 (`docs/plan/week2-3.md §⑥`).
 *
 * 본 인터페이스는 `JpaRepository` *만* 상속하며 별도 메서드를 갖지 않는다 — 사용자별 페이지 조회 등 도메인성
 * 쿼리는 `WishlistRepositoryImpl` 안의 **QueryDSL** 로 표현 (문자열 JPQL `@Query` 미사용).
 */
interface WishlistJpaRepository : JpaRepository<WishlistModel, WishlistId>
