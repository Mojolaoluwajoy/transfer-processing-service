package com.transferservice.transfer_processing_service.mock.fineract;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = MockFineractController.class)
class MockFineractExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(MockFineractException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		problem.setProperty("code", ex.code());
		return problem;
	}

}
