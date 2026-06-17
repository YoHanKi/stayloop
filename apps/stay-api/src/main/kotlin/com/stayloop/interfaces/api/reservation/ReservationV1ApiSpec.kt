package com.stayloop.interfaces.api.reservation

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Reservation V1 API", description = "Stayloop 예약 API 입니다.")
interface ReservationV1ApiSpec {
    @Operation(summary = "예약 생성", description = "기간·인원·객실로 예약을 만들고 날짜별 재고를 차감합니다. Idempotency-Key 헤더로 중복 요청을 1건으로 막습니다.")
    fun reserve(
        loginUser: LoginUser,
        request: ReservationV1Dto.ReserveRequest,
        idempotencyKey: String?,
    ): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(summary = "예약 취소", description = "본인 예약을 취소하고 재고를 복원합니다. 타인 예약은 403.")
    fun cancel(loginUser: LoginUser, reservationId: Long): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(summary = "예약 단건 조회", description = "본인 예약을 조회합니다. 타인 예약은 403.")
    fun getReservation(loginUser: LoginUser, reservationId: Long): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(summary = "내 예약 목록", description = "본인의 예약 목록을 체크인 최신순으로 조회합니다.")
    fun getMyReservations(
        loginUser: LoginUser,
        page: Int,
        size: Int,
    ): ApiResponse<List<ReservationV1Dto.ReservationResponse>>
}
