package com.transferservice.transfer_processing_service.transfer.api;

import java.util.List;
import java.util.Map;

import com.transferservice.transfer_processing_service.partner.SourceAccountNotPermittedException;
import com.transferservice.transfer_processing_service.transfer.IdempotencyConflictException;
import com.transferservice.transfer_processing_service.transfer.TransferNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TransferController.class)
class TransferApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(IdempotencyConflictException ex) {
		return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(SourceAccountNotPermittedException ex) {
		return problem(HttpStatus.FORBIDDEN, "SOURCE_ACCOUNT_NOT_PERMITTED", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(TransferNotFoundException ex) {
		// Also returned for another partner's transfer, so existence is not revealed.
		return problem(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(MethodArgumentNotValidException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
		List<Map<String, Object>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> Map.<String, Object>of("field", error.getField(), "message",
					String.valueOf(error.getDefaultMessage())))
			.toList();
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler
	ProblemDetail handle(HttpMessageNotReadableException ex) {
		return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is not valid JSON for this endpoint");
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

}
