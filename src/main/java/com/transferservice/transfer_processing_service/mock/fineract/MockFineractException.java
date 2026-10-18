package com.transferservice.transfer_processing_service.mock.fineract;

import org.springframework.http.HttpStatus;

class MockFineractException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	MockFineractException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	HttpStatus status() {
		return this.status;
	}

	String code() {
		return this.code;
	}

	static MockFineractException notFound(String code, String message) {
		return new MockFineractException(HttpStatus.NOT_FOUND, code, message);
	}

	static MockFineractException conflict(String code, String message) {
		return new MockFineractException(HttpStatus.CONFLICT, code, message);
	}

	static MockFineractException unprocessable(String code, String message) {
		return new MockFineractException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

}
