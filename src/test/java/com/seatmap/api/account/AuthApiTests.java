package com.seatmap.api.account;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seatmap.api.TestAccounts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signing up, logging in and out, with the real session cookie and CSRF protection. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTests {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyDatabase() {
		TestAccounts.emptyDatabase(this.jdbc);
	}

	@Test
	void signsUpIntoANewOrganizationAndStaysLoggedIn() throws Exception {
		MvcResult result = register("ada@example.com").andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("ada@example.com"))
			.andExpect(jsonPath("$.organizations[0].name").value("Ada's Arena"))
			.andExpect(jsonPath("$.organizations[0].role").value("ADMIN"))
			.andReturn();
		Cookie session = result.getResponse().getCookie("SESSION");
		assertThat(session).isNotNull();

		this.mvc.perform(get("/api/auth/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Ada"));
		this.mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void refusesASecondAccountWithTheSameEmail() throws Exception {
		register("ada@example.com").andExpect(status().isCreated());
		register("ADA@example.com").andExpect(status().isConflict());
	}

	@Test
	void refusesShortPasswords() throws Exception {
		this.mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "ada@example.com", "password": "short", "displayName": "Ada", "organizationName": "Arena"}"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void logsInAndOut() throws Exception {
		register("ada@example.com").andExpect(status().isCreated());
		Cookie session = login("ada@example.com", PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.organizations[0].role").value("ADMIN"))
			.andReturn()
			.getResponse()
			.getCookie("SESSION");

		this.mvc.perform(post("/api/auth/logout").with(csrf()).cookie(session)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void givesTheSameAnswerForAWrongPasswordAndAnUnknownEmail() throws Exception {
		register("ada@example.com").andExpect(status().isCreated());
		login("ada@example.com", "wrong password!").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("The email or password is wrong"));
		login("nobody@example.com", PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("The email or password is wrong"));
	}

	@Test
	void locksTheAccountAfterTenFailedLogins() throws Exception {
		register("ada@example.com").andExpect(status().isCreated());
		for (int i = 0; i < 10; i++) {
			login("ada@example.com", "wrong password!").andExpect(status().isUnauthorized());
		}
		login("ada@example.com", PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Too many failed logins; try again in 15 minutes"));

		this.jdbc.update("UPDATE app_user SET locked_until = now() - interval '1 second'");
		login("ada@example.com", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void refusesChangesWithoutTheCsrfToken() throws Exception {
		this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"ada@example.com\", \"password\": \"%s\"}".formatted(PASSWORD)))
			.andExpect(status().isForbidden());
	}

	private ResultActions register(String email) throws Exception {
		return this.mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s", "displayName": "Ada", "organizationName": "Ada's Arena"}"""
			.formatted(email, PASSWORD)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
	}

}
