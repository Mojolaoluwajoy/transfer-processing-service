package com.transferservice.transfer_processing_service.mock;

import org.springframework.http.HttpStatus;

public class MockApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	MockApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public static MockApiException notFound(String code, String message) {
		return new MockApiException(HttpStatus.NOT_FOUND, code, message);
	}

	public static MockApiException conflict(String code, String message) {
		return new MockApiException(HttpStatus.CONFLICT, code, message);
	}

	public static MockApiException unprocessable(String code, String message) {
		return new MockApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

}
