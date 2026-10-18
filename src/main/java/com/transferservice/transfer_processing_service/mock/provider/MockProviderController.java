package com.transferservice.transfer_processing_service.mock.provider;

import java.util.concurrent.atomic.AtomicLong;

import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.PaymentRequest;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.PaymentResponse;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.ResolveRequest;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.StatsResponse;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderService.Submission;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock/provider")
class MockProviderController {

	private final MockProviderService service;

	private final MockProviderCallbackSender callbackSender;

	private final long slowResponseMs;

	private final AtomicLong submissionRequests = new AtomicLong();

	MockProviderController(MockProviderService service, MockProviderCallbackSender callbackSender,
			@Value("${mock.provider.slow-response-ms}") long slowResponseMs) {
		this.service = service;
		this.callbackSender = callbackSender;
		this.slowResponseMs = slowResponseMs;
	}

	@PostMapping("/payments")
	PaymentResponse submit(@Valid @RequestBody PaymentRequest request) throws InterruptedException {
		this.submissionRequests.incrementAndGet();
		Submission submission = this.service.submit(request);
		// Everything below runs after the payment has committed. Replays answer promptly,
		// so a client that timed out can still learn the stored result by resubmitting.
		if (!submission.response().replayed()) {
			if (submission.scenario().has(MockProviderScenario.EARLY_CALLBACK)) {
				this.callbackSender.deliverNow(request.paymentReference());
			}
			if (submission.scenario().has(MockProviderScenario.SLOW_RESPONSE)) {
				Thread.sleep(this.slowResponseMs);
			}
		}
		return submission.response();
	}

	@GetMapping("/payments/{paymentReference}")
	PaymentResponse getPayment(@PathVariable String paymentReference) {
		return this.service.getPayment(paymentReference);
	}

	@PostMapping("/payments/{paymentReference}/resolve")
	PaymentResponse resolve(@PathVariable String paymentReference, @Valid @RequestBody ResolveRequest request) {
		return this.service.resolve(paymentReference, request.status(),
				!Boolean.FALSE.equals(request.sendCallback()));
	}

	@GetMapping("/stats")
	StatsResponse stats() {
		return new StatsResponse(this.submissionRequests.get(), this.service.countPayments("ANY"),
				this.service.countPayments("SUCCEEDED"), this.service.countPayments("FAILED"),
				this.service.countPayments("PROCESSING"), this.service.countDeliveredCallbacks());
	}

}
