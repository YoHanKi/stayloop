package com.stayloop.infrastructure.cache

import java.time.LocalDate

/**
 * `availability:{roomTypeId}:{yyyymmdd}` cache 의 직렬화 단위. (`docs/plan/week5.md` PR4 A-5, D-4)
 *
 * 본 cache 의 본질은 *결제 직전 결정에는 사용 금지* (D-5) — *검색 → 클릭 → 가용 조회* 의 짧은 TTL (10s)
 * 동적 cache. 결제 흐름 (`ReservationFacade.reserve`) 은 비관적 락으로 *DB 행 직접 락* 을 잡으므로 본
 * cache 를 우회한다.
 *
 * **필드 정합 — `DailyRoomInventoryModel` + `DailyRoomRateModel` 의 합 단위 박제**: cache hit 시점에 *재고
 * 가용 수 + 단가* 두 정보가 한 번에 결정. 운영의 *Step 2 (PR3)* 가 inventory + rate 를 inner join 한 결과 한
 * 줄에 대응.
 *
 * **`pricePerNight` 단위는 정수 원 (Long)**: `Money` VO 직접 직렬화는 도메인 누출 (verify-code §3) — cache
 * 는 plain primitive 만. Money 래핑은 호출자 (Facade) 책임.
 */
data class RoomDailyAvailability(
    val roomTypeId: Long,
    val date: LocalDate,
    val totalRooms: Int,
    val reservedRooms: Int,
    val pricePerNight: Long,
) {
    val availableRooms: Int get() = totalRooms - reservedRooms
}
