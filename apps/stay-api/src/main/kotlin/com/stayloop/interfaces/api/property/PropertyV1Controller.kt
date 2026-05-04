package com.stayloop.interfaces.api.property

import com.stayloop.application.property.PropertyFacade
import com.stayloop.interfaces.api.ApiResponse
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
        @RequestParam checkIn: LocalDate,
        @RequestParam checkOut: LocalDate,
        @RequestParam(defaultValue = "1") guests: Int,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) sort: String?,
    ): ApiResponse<PropertyV1Dto.SearchPageResponse> {
        val request = PropertyV1Dto.SearchRequest(
            city = city,
            checkIn = checkIn,
            checkOut = checkOut,
            guests = guests,
            page = page,
            size = size,
            sort = sort,
        )
        return propertyFacade.search(request.toCriteria())
            .let { PropertyV1Dto.SearchPageResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{propertyId}")
    override fun getDetail(
        @PathVariable propertyId: Long,
    ): ApiResponse<PropertyV1Dto.PropertyDetailResponse> {
        return propertyFacade.getDetail(propertyId)
            .let { PropertyV1Dto.PropertyDetailResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{propertyId}/rooms")
    override fun getAvailableRooms(
        @PathVariable propertyId: Long,
        @RequestParam checkIn: LocalDate,
        @RequestParam checkOut: LocalDate,
        @RequestParam(defaultValue = "1") guests: Int,
    ): ApiResponse<List<PropertyV1Dto.RoomAvailabilityResponse>> {
        val request = PropertyV1Dto.AvailabilityRequest(
            checkIn = checkIn,
            checkOut = checkOut,
            guests = guests,
        )
        return propertyFacade.getAvailableRooms(request.toQuery(propertyId))
            .map(PropertyV1Dto.RoomAvailabilityResponse::from)
            .let { ApiResponse.success(it) }
    }
}
