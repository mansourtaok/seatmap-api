package com.seatmap.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * Staff log in with a session cookie; buyers need no login. CSRF protection uses the
 * {@code XSRF-TOKEN} cookie that Angular's HttpClient sends back as {@code X-XSRF-TOKEN}.
 * Anything not listed as open needs a login; which organization's data a user may touch is
 * checked in the services.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository)
			throws Exception {
		return http
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/public/tickets/resend").permitAll()
				// Buyers: see what's left and hold, view or release their own reservation. The
				// services only show them events that are on sale.
				.requestMatchers(HttpMethod.GET, "/api/events/*/availability", "/api/events/*/seat-statuses")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/events/*/reservations").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/reservations/*", "/api/reservations/*/tickets.pdf").permitAll()
				.requestMatchers(HttpMethod.PUT, "/api/reservations/*/buyer").permitAll()
				.requestMatchers(HttpMethod.DELETE, "/api/reservations/*").permitAll()
				.anyRequest().authenticated())
			.csrf((csrf) -> csrf.spa())
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.cors(Customizer.withDefaults())
			.securityContext((context) -> context.securityContextRepository(securityContextRepository))
			.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.exceptionHandling((errors) -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.logout((logout) -> logout.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.formLogin((form) -> form.disable())
			.httpBasic((basic) -> basic.disable())
			.build();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/** bcrypt today; the stored hashes say which algorithm made them, so it can change later. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
