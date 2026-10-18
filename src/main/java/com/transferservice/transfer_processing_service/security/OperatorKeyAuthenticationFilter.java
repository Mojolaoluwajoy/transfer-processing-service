package com.transferservice.transfer_processing_service.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.transferservice.transfer_processing_service.common.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates operators by the {@code X-Operator-Key} header against a SHA-256 hash from
 * configuration. With no hash configured, operator access is disabled entirely.
 */
class OperatorKeyAuthenticationFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Operator-Key";

	private final byte[] expectedHash;

	private final AuthenticationEntryPoint entryPoint;

	OperatorKeyAuthenticationFilter(String operatorKeySha256, AuthenticationEntryPoint entryPoint) {
		this.expectedHash = StringUtils.hasText(operatorKeySha256)
				? operatorKeySha256.trim().toLowerCase().getBytes(StandardCharsets.UTF_8) : null;
		this.entryPoint = entryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String key = request.getHeader(HEADER);
		if (!StringUtils.hasText(key)) {
			chain.doFilter(request, response);
			return;
		}
		byte[] presented = Hashing.sha256Hex(key).getBytes(StandardCharsets.UTF_8);
		if (this.expectedHash == null || !MessageDigest.isEqual(this.expectedHash, presented)) {
			SecurityContextHolder.clearContext();
			this.entryPoint.commence(request, response, new BadCredentialsException("Invalid operator key"));
			return;
		}
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator", null,
					List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
		chain.doFilter(request, response);
	}

}
