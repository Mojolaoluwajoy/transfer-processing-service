package com.transferservice.transfer_processing_service.callback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v2/provider/events}.
 */
public record ProviderCallbackPayload(@NotBlank @Size(max = 100) String eventId,
		@NotBlank @Size(max = 60) String paymentReference, String providerPaymentId,
		@NotBlank @Size(max = 20) String status, String failureReason) {

}
