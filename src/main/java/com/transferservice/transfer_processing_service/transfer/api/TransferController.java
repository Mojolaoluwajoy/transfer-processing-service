package com.transferservice.transfer_processing_service.transfer.api;

import java.net.URI;

import com.transferservice.transfer_processing_service.partner.PartnerPrincipal;
import com.transferservice.transfer_processing_service.transfer.TransferService;
import com.transferservice.transfer_processing_service.transfer.TransferService.Initiation;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/transfers")
class TransferController {

	private final TransferService transfers;

	TransferController(TransferService transfers) {
		this.transfers = transfers;
	}

	/**
	 * 202 when the transfer is newly accepted for asynchronous processing; 200 with the
	 * existing transfer when the same request is repeated.
	 */
	@PostMapping
	ResponseEntity<TransferResponse> create(@AuthenticationPrincipal PartnerPrincipal partner,
			@Valid @RequestBody CreateTransferRequest request) {
		Initiation initiation = this.transfers.initiate(partner, request.toNewTransfer());
		TransferResponse body = TransferResponse.from(initiation.transfer());
		return ResponseEntity.status(initiation.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
			.location(URI.create("/api/v2/transfers/" + body.reference()))
			.body(body);
	}

	@GetMapping("/{reference}")
	TransferResponse get(@AuthenticationPrincipal PartnerPrincipal partner, @PathVariable String reference) {
		return TransferResponse.from(this.transfers.get(partner, reference));
	}

}
