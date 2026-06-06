package com.stayloop.interfaces.api.property

import com.stayloop.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate

@Tag(name = "Property V1 API", description = "Stayloop 숙소 검색·상세 API 입니다.")
interface PropertyV1ApiSpec {
    @Operation(summary = "숙소 검색", description = "도시·기간·인원으로 가용 객실이 있는 숙소를 최저 합산가와 함께 조회합니다.")
    fun search(
        city: String,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guestCount: Int,
        sort: String,
        page: Int,
        size: Int,
    ): ApiResponse<List<PropertyV1Dto.SearchResponse>>

    @Operation(summary = "숙소 상세", description = "숙소의 정적 정보와 객실 타입 목록을 조회합니다.")
    fun getDetail(propertyId: Long): ApiResponse<PropertyV1Dto.DetailResponse>

    @Operation(summary = "가용 객실 조회", description = "특정 숙소의 기간·인원별 가용 객실과 합산가를 조회합니다.")
    fun getAvailableRooms(
        propertyId: Long,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guestCount: Int,
    ): ApiResponse<List<PropertyV1Dto.RoomAvailabilityResponse>>
}
