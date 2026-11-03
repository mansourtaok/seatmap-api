package com.seatmap.api;

import java.util.List;
import java.util.UUID;

import com.seatmap.api.account.SeatmapUser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Staff accounts for tests, written straight to the database. */
public final class TestAccounts {

	private TestAccounts() {
	}

	/** Empties every table, accounts included. */
	public static void emptyDatabase(JdbcTemplate jdbc) {
		jdbc.execute("TRUNCATE layout, organization, app_user, SPRING_SESSION CASCADE");
	}

	/** A user who is {@code role} of a new organization; returns the user, the organization is {@link Staff#organizationId}. */
	public static Staff staff(JdbcTemplate jdbc, String name, String role) {
		UUID organizationId = UUID.randomUUID();
		jdbc.update("INSERT INTO organization (id, name, created_at) VALUES (?, ?, now())", organizationId,
				name + "'s organization");
		return join(jdbc, user(jdbc, name), organizationId, role);
	}

	/** A user with no organization yet. */
	public static SeatmapUser user(JdbcTemplate jdbc, String name) {
		UUID userId = UUID.randomUUID();
		String email = name.toLowerCase() + "-" + userId.toString().substring(0, 8) + "@example.com";
		jdbc.update("""
				INSERT INTO app_user (id, email, display_name, password_hash, created_at)
				VALUES (?, ?, ?, '{noop}not-used', now())""", userId, email, name);
		return new SeatmapUser(userId, email, name);
	}

	public static Staff join(JdbcTemplate jdbc, SeatmapUser user, UUID organizationId, String role) {
		jdbc.update("INSERT INTO membership (organization_id, user_id, role, created_at) VALUES (?, ?, ?, now())",
				organizationId, user.id(), role);
		return new Staff(user, organizationId);
	}

	/** Logs every request in as {@code user}, with a CSRF token, as the web app would send. */
	public static MockMvc mockMvcAs(WebApplicationContext context, SeatmapUser user) {
		return MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.defaultRequest(get("/").with(as(user)).with(csrf()))
			.build();
	}

	public static RequestPostProcessor as(SeatmapUser user) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
	}

	public record Staff(SeatmapUser user, UUID organizationId) {
	}

}
