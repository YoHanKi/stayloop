package com.stayloop.domain.inventory

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 재고 동시 차감/복원의 도메인 규칙을 소유한다(04-a §8, 04-b §5-1).
 *
 * 차감 자체는 [DailyRoomInventoryRepository] 의 조건부 원자 연산(가용 일자만 갱신, 영향 행 수 반환)에 맡기고,
 * 본 서비스는 그 **사실(차감된 일자 수)을 비즈니스 규칙으로 해석**한다 — 전 일자가 차감되지 않으면 일부가
 * 매진/부재이므로 CONFLICT 로 전체 실패시킨다(부분 차감 금지). 영향 행 수 검사를 영속성 어댑터가 아니라
 * 도메인에 둬, "0 행 = 품절" 같은 업무 판단이 쿼리 계층에 새지 않게 한다.
 *
 * 호출은 Facade 의 트랜잭션 안에서 이뤄진다 — 전체 실패 시 이미 차감된 행은 롤백으로 되돌아간다.
 */
@Service
class DailyRoomInventoryService(
    private val inventoryRepository: DailyRoomInventoryRepository,
) {
    /** [dates] 전 일자의 재고를 1 씩 차감한다. 한 일자라도 매진/부재면 CONFLICT. */
    fun reserve(roomTypeId: Long, dates: List<LocalDate>) {
        val target = dates.distinct()
        val deducted = inventoryRepository.deductIfAvailable(roomTypeId, target)
        if (deducted != target.size) {
            throw CoreException(ErrorType.CONFLICT, "예약 가능한 객실이 없는 날짜가 있습니다.")
        }
    }

    /** [dates] 전 일자의 재고를 1 씩 복원한다. 복원할 예약분이 없는 일자가 있으면 BAD_REQUEST. */
    fun release(roomTypeId: Long, dates: List<LocalDate>) {
        val target = dates.distinct()
        val restored = inventoryRepository.restore(roomTypeId, target)
        if (restored != target.size) {
            throw CoreException(ErrorType.BAD_REQUEST, "복원할 예약분이 없는 날짜가 있습니다.")
        }
    }
}
