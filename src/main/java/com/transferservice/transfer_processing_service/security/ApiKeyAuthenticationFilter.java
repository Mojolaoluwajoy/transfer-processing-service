package com.transferservice.transfer_processing_service.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.transferservice.transfer_processing_service.partner.PartnerAuthenticationService;
import com.transferservice.transfer_processing_service.partner.PartnerPrincipal;
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
 * Authenticates partners by the {@code X-API-Key} header. Requests without the header pass
 * through unauthenticated (and are rejected later if the path needs a partner); a key that
 * does not match an active partner is rejected immediately.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	static final String HEADER = "X-API-Key";

	private final PartnerAuthenticationService partners;

	private final AuthenticationEntryPoint entryPoint;

	ApiKeyAuthenticationFilter(PartnerAuthenticationService partners, AuthenticationEntryPoint entryPoint) {
		this.partners = partners;
		this.entryPoint = entryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String apiKey = request.getHeader(HEADER);
		if (!StringUtils.hasText(apiKey)) {
			chain.doFilter(request, response);
			return;
		}
		Optional<PartnerPrincipal> partner = this.partners.authenticate(apiKey);
		if (partner.isEmpty()) {
			SecurityContextHolder.clearContext();
			this.entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
			return;
		}
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(partner.get(), null,
					List.of(new SimpleGrantedAuthority("ROLE_PARTNER"))));
		chain.doFilter(request, response);
	}

}
