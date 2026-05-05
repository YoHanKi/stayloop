package com.stayloop.interfaces.api.reservation

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate

@Tag(name = "Reservation V1 API", description = "Stayloop 예약 생성·취소·조회 API 입니다.")
interface ReservationV1ApiSpec {
    @Operation(
        summary = "예약 생성",
        description = "객실 타입 + 기간 + 인원 + 예약자 정보로 예약을 생성합니다 (PENDING 상태). 트랜잭션 내에서 일자별 재고를 차감합니다.",
    )
    fun reserve(
        loginUser: LoginUser,
        request: ReservationV1Dto.CreateRequest,
    ): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(
        summary = "예약 취소",
        description = "본인 예약만 취소 가능. 상태 머신 상 CHECKED_IN 이후는 거절됩니다 (CONFLICT).",
    )
    fun cancel(
        loginUser: LoginUser,
        reservationId: Long,
    ): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(
        summary = "예약 단건 조회",
        description = "본인 예약만 조회 가능 (FORBIDDEN).",
    )
    fun getReservation(
        loginUser: LoginUser,
        reservationId: Long,
    ): ApiResponse<ReservationV1Dto.ReservationResponse>

    @Operation(
        summary = "본인 예약 목록 조회",
        description = "기간과 *겹치는* 예약을 checkIn DESC 순으로 반환합니다.",
    )
    fun getMyReservations(
        loginUser: LoginUser,
        from: LocalDate,
        to: LocalDate,
    ): ApiResponse<List<ReservationV1Dto.ReservationResponse>>
}
