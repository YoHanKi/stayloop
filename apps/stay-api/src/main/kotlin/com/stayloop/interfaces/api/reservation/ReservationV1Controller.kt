package com.stayloop.interfaces.api.reservation

import com.stayloop.application.reservation.ReservationFacade
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationV1Controller(
    private val reservationFacade: ReservationFacade,
) : ReservationV1ApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun reserve(
        loginUser: LoginUser,
        @RequestBody request: ReservationV1Dto.CreateRequest,
    ): ApiResponse<ReservationV1Dto.ReservationResponse> {
        return reservationFacade.reserve(request.toCommand(loginUser.loginId))
            .let { ReservationV1Dto.ReservationResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PostMapping("/{reservationId}/cancel")
    override fun cancel(
        loginUser: LoginUser,
        @PathVariable reservationId: Long,
    ): ApiResponse<ReservationV1Dto.ReservationResponse> {
        return reservationFacade.cancel(loginUser.loginId, reservationId)
            .let { ReservationV1Dto.ReservationResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{reservationId}")
    override fun getReservation(
        loginUser: LoginUser,
        @PathVariable reservationId: Long,
    ): ApiResponse<ReservationV1Dto.ReservationResponse> {
        return reservationFacade.getReservation(loginUser.loginId, reservationId)
            .let { ReservationV1Dto.ReservationResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping
    override fun getMyReservations(
        loginUser: LoginUser,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): ApiResponse<List<ReservationV1Dto.ReservationResponse>> {
        val period = StayPeriod(from, to)
        return reservationFacade.getMyReservations(loginUser.loginId, period)
            .map(ReservationV1Dto.ReservationResponse::from)
            .let { ApiResponse.success(it) }
    }
}
