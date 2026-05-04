package com.stayloop.interfaces.api.property

import com.stayloop.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate

@Tag(name = "Property V1 API", description = "Stayloop 숙소 검색 / 상세 API 입니다.")
interface PropertyV1ApiSpec {
    @Operation(
        summary = "숙소 검색",
        description = "도시 + 기간 + 인원 기준으로 숙소를 검색합니다. 가용 객실 0 인 숙소는 결과에서 제외됩니다.",
    )
    fun search(
        city: String,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guests: Int,
        page: Int,
        size: Int,
        sort: String?,
    ): ApiResponse<PropertyV1Dto.SearchPageResponse>

    @Operation(
        summary = "숙소 상세",
        description = "숙소의 정적 정보(설명·이미지·정책·객실 타입 목록) 를 반환합니다. 가용성/합산가는 별도 API.",
    )
    fun getDetail(propertyId: Long): ApiResponse<PropertyV1Dto.PropertyDetailResponse>

    @Operation(
        summary = "숙소의 객실 타입별 가용성",
        description = "기간 + 인원 조합에 대한 객실 타입별 가용성과 합산가를 반환합니다.",
    )
    fun getAvailableRooms(
        propertyId: Long,
        checkIn: LocalDate,
        checkOut: LocalDate,
        guests: Int,
    ): ApiResponse<List<PropertyV1Dto.RoomAvailabilityResponse>>
}
