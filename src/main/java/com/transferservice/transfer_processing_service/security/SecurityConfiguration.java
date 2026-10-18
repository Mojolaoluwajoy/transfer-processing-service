package com.transferservice.transfer_processing_service.security;

import com.transferservice.transfer_processing_service.partner.PartnerAuthenticationService;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, PartnerAuthenticationService partners,
			JsonMapper jsonMapper) {
		ProblemDetailSecurityHandler problems = new ProblemDetailSecurityHandler(jsonMapper);
		http.csrf((csrf) -> csrf.disable())
			.httpBasic((basic) -> basic.disable())
			.formLogin((form) -> form.disable())
			.logout((logout) -> logout.disable())
			.requestCache((cache) -> cache.disable())
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling((ex) -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
			.addFilterBefore(new ApiKeyAuthenticationFilter(partners, problems), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests((requests) -> requests
				// Authenticated by HMAC signature in the callback handler, not by partner key.
				.requestMatchers("/api/v2/provider/events").permitAll()
				.requestMatchers("/api/v2/**").hasRole("PARTNER")
				// The mocks stand in for external systems and would not ship with the service.
				.requestMatchers("/mock/**").permitAll()
				.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers("/error").permitAll()
				.anyRequest().denyAll());
		return http.build();
	}

}
