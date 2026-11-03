package com.seatmap.api.account;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing up and logging in. Logging in starts a session: the browser gets an HttpOnly
 * session cookie. Logging out is {@code POST /api/auth/logout}, handled by Spring Security.
 */
@RestController
class AuthController {

	private final AccountService accounts;

	private final SecurityContextRepository securityContextRepository;

	private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
		.getContextHolderStrategy();

	AuthController(AccountService accounts, SecurityContextRepository securityContextRepository) {
		this.accounts = accounts;
		this.securityContextRepository = securityContextRepository;
	}

	/** Creates an account and its organization, and logs in. */
	@PostMapping("/api/auth/register")
	ResponseEntity<AccountViews.Me> register(@Valid @RequestBody AccountViews.Register body,
			HttpServletRequest request, HttpServletResponse response) {
		signIn(this.accounts.register(body), request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(this.accounts.me());
	}

	@PostMapping("/api/auth/login")
	AccountViews.Me login(@Valid @RequestBody AccountViews.Login body, HttpServletRequest request,
			HttpServletResponse response) {
		signIn(this.accounts.login(body.email(), body.password()), request, response);
		return this.accounts.me();
	}

	@GetMapping("/api/auth/me")
	AccountViews.Me me() {
		return this.accounts.me();
	}

	private void signIn(SeatmapUser user, HttpServletRequest request, HttpServletResponse response) {
		// A new session id on login, so an id planted before it (session fixation) is useless.
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
		this.securityContextHolderStrategy.setContext(context);
		this.securityContextRepository.saveContext(context, request, response);
	}

}
