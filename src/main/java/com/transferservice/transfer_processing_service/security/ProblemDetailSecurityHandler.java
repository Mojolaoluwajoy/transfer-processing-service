package com.transferservice.transfer_processing_service.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes 401 and 403 responses in the same problem+json format as the rest of the API.
 */
class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	ProblemDetailSecurityHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid "
				+ ApiKeyAuthenticationFilter.HEADER + " (or, for operator endpoints, "
				+ OperatorKeyAuthenticationFilter.HEADER + ") header is required");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		if (status == HttpStatus.UNAUTHORIZED) {
			response.setHeader("WWW-Authenticate", "ApiKey header=\"" + ApiKeyAuthenticationFilter.HEADER + "\"");
		}
		this.jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
