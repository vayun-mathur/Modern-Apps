package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/** A refund quote for cancelling an order (create → confirm). */
@Serializable
data class CancellationDto(
    val id: String = "",
    val orderId: String = "",
    val refundAmount: String = "0",
    val refundCurrency: String = "USD",
    val refundTo: String = "",
)

/** One offered alternative when changing an order. */
@Serializable
data class ChangeOfferDto(
    val id: String = "",
    val changeTotalAmount: String = "0",
    val changeTotalCurrency: String = "USD",
    val newTotalAmount: String = "0",
    val newTotalCurrency: String = "USD",
    val slices: List<SliceDto> = emptyList(),
)

/** Result of creating a change request: the request id + priced offers. */
@Serializable
data class ChangeRequestResultDto(
    val id: String = "",
    val offers: List<ChangeOfferDto> = emptyList(),
)

/** A slice to add during a change (origin/destination/date). */
@Serializable
data class SearchSliceInputDto(
    val origin: String = "",
    val destination: String = "",
    val date: String = "",
)

/** Body for starting an order change. */
@Serializable
data class ChangeRequestInputDto(
    val orderId: String = "",
    val removeSliceIds: List<String> = emptyList(),
    val add: List<SearchSliceInputDto> = emptyList(),
    val cabin: String? = null,
)

/** Body for adding services to a booked order. */
@Serializable
data class AddServicesInputDto(
    val services: List<ServiceSelectionDto> = emptyList(),
    val amount: String = "0",
    val currency: String = "USD",
)
