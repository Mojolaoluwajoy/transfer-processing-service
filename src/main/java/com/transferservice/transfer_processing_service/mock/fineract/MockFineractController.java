package com.transferservice.transfer_processing_service.mock.fineract;

import java.util.concurrent.atomic.AtomicLong;

import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.AccountRequest;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.AccountResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.DebitRequest;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.FaultRequest;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.OperationResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.RefundRequest;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.StatsResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock/fineract")
class MockFineractController {

	private final MockFineractService service;

	private final MockFineractFaults faults;

	private final AtomicLong debitRequests = new AtomicLong();

	private final AtomicLong refundRequests = new AtomicLong();

	MockFineractController(MockFineractService service, MockFineractFaults faults) {
		this.service = service;
		this.faults = faults;
	}

	@PostMapping("/debits")
	OperationResponse debit(@Valid @RequestBody DebitRequest request) {
		this.debitRequests.incrementAndGet();
		OperationResponse response = this.service.debit(request);
		this.faults.afterCommit();
		return response;
	}

	@PostMapping("/refunds")
	OperationResponse refund(@Valid @RequestBody RefundRequest request) {
		this.refundRequests.incrementAndGet();
		OperationResponse response = this.service.refund(request);
		this.faults.afterCommit();
		return response;
	}

	@GetMapping("/operations/{operationReference}")
	OperationResponse getOperation(@PathVariable String operationReference) {
		return this.service.getOperation(operationReference);
	}

	@PutMapping("/accounts/{accountNumber}")
	AccountResponse upsertAccount(@PathVariable String accountNumber, @Valid @RequestBody AccountRequest request) {
		return this.service.upsertAccount(accountNumber, request.balance());
	}

	@GetMapping("/accounts/{accountNumber}")
	AccountResponse getAccount(@PathVariable String accountNumber) {
		return this.service.getAccount(accountNumber);
	}

	@GetMapping("/stats")
	StatsResponse stats() {
		return new StatsResponse(this.debitRequests.get(), this.refundRequests.get(),
				this.service.countOperations("DEBIT", "COMMITTED"), this.service.countOperations("DEBIT", "REJECTED"),
				this.service.countOperations("REFUND", "COMMITTED"));
	}

	@PostMapping("/faults")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void injectFault(@Valid @RequestBody FaultRequest request) {
		this.faults.delayNextResponses(request.count(), request.delayMs());
	}

	@DeleteMapping("/faults")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void clearFaults() {
		this.faults.clear();
	}

}
