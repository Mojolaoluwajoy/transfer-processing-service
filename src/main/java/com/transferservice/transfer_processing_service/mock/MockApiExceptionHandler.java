package com.transferservice.transfer_processing_service.mock;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = MockApiExceptionHandler.class)
class MockApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(MockApiException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		problem.setProperty("code", ex.code());
		return problem;
	}

}
