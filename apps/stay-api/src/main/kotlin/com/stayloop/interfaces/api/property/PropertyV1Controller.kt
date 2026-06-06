package com.stayloop.interfaces.api.property

import com.stayloop.application.property.PropertyFacade
import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.PropertySortKey
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/properties")
class PropertyV1Controller(
    private val propertyFacade: PropertyFacade,
) : PropertyV1ApiSpec {
    @GetMapping("/search")
    override fun search(
        @RequestParam city: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkIn: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkOut: LocalDate,
        @RequestParam(defaultValue = "2") guestCount: Int,
        @RequestParam(defaultValue = "recommended") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<PropertyV1Dto.SearchResponse>> {
        val criteria = PropertySearchCriteria(
            city = city,
            checkIn = checkIn,
            checkOut = checkOut,
            guestCount = guestCount,
            sortKey = parseSort(sort),
            page = page,
            size = size,
        )
        return propertyFacade.search(criteria)
            .map { PropertyV1Dto.SearchResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{propertyId}")
    override fun getDetail(
        @PathVariable propertyId: Long,
    ): ApiResponse<PropertyV1Dto.DetailResponse> =
        propertyFacade.getDetail(propertyId)
            .let { PropertyV1Dto.DetailResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{propertyId}/rooms")
    override fun getAvailableRooms(
        @PathVariable propertyId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkIn: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkOut: LocalDate,
        @RequestParam(defaultValue = "2") guestCount: Int,
    ): ApiResponse<List<PropertyV1Dto.RoomAvailabilityResponse>> =
        propertyFacade.getAvailableRooms(RoomAvailabilityQuery(propertyId, checkIn, checkOut, guestCount))
            .map { PropertyV1Dto.RoomAvailabilityResponse.from(it) }
            .let { ApiResponse.success(it) }

    private fun parseSort(sort: String): PropertySortKey =
        runCatching { PropertySortKey.valueOf(sort.uppercase()) }
            .getOrElse { throw CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 정렬 키입니다: $sort") }
}
