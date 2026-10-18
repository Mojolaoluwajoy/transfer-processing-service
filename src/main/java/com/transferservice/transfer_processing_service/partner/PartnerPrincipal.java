package com.transferservice.transfer_processing_service.partner;

import java.util.UUID;

/**
 * The authenticated partner for the current request, derived from its API key. Controllers
 * take partner identity from here, never from the request body.
 */
public record PartnerPrincipal(UUID partnerId, String name) {

}
