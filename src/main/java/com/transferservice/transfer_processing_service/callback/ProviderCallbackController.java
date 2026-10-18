package com.transferservice.transfer_processing_service.callback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

import com.transferservice.transfer_processing_service.common.Hashing;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives provider callbacks. Authenticated by an HMAC-SHA256 signature over the raw body
 * rather than a partner key. Any callback that is stored gets a 200, whatever its outcome,
 * so the provider does not keep redelivering it.
 */
@RestController
class ProviderCallbackController {

	static final String SIGNATURE_HEADER = "X-Provider-Signature";

	private final ProviderCallbackService service;

	private final JsonMapper jsonMapper;

	private final Validator validator;

	private final String secret;

	ProviderCallbackController(ProviderCallbackService service, JsonMapper jsonMapper, Validator validator,
			@Value("${provider.callback-secret}") String secret) {
		this.service = service;
		this.jsonMapper = jsonMapper;
		this.validator = validator;
		this.secret = secret;
	}

	@PostMapping("/api/v2/provider/events")
	ResponseEntity<?> receive(@RequestBody byte[] body,
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {
		if (!validSignature(body, signature)) {
			return problem(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Missing or invalid " + SIGNATURE_HEADER);
		}
		ProviderCallbackPayload callback;
		try {
			callback = this.jsonMapper.readValue(body, ProviderCallbackPayload.class);
		}
		catch (JacksonException ex) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_CALLBACK", "Callback body is not valid JSON");
		}
		Set<ConstraintViolation<ProviderCallbackPayload>> violations = this.validator.validate(callback);
		if (!violations.isEmpty()) {
			return problem(HttpStatus.BAD_REQUEST, "MALFORMED_CALLBACK", "Callback is missing required fields");
		}
		CallbackOutcome outcome = this.service.handle(callback, new String(body, StandardCharsets.UTF_8));
		return ResponseEntity.ok(Map.of("eventId", callback.eventId(), "outcome", outcome.name()));
	}

	private boolean validSignature(byte[] body, String signature) {
		if (signature == null) {
			return false;
		}
		byte[] expected = Hashing.hmacSha256Hex(this.secret, body).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return ResponseEntity.status(status).body(problem);
	}

}
