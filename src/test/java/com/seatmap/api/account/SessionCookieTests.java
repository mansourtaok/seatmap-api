package com.seatmap.api.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seatmap.api.TestAccounts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cookies as a browser gets them, from a real server: MockMvc doesn't show the attributes
 * Spring Session writes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionCookieTests {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void emptyDatabase() {
		TestAccounts.emptyDatabase(this.jdbc);
	}

	@Test
	void givesTheBrowserAReadableCsrfCookieAndAnHttpOnlySessionCookie() throws Exception {
		HttpResponse<String> first = this.http.send(HttpRequest.newBuilder(uri("/actuator/health")).build(),
				HttpResponse.BodyHandlers.ofString());
		String xsrf = cookie(first, "XSRF-TOKEN");
		assertThat(xsrf).doesNotContainIgnoringCase("HttpOnly");
		String token = xsrf.substring("XSRF-TOKEN=".length(), xsrf.indexOf(';'));

		HttpResponse<String> registered = this.http.send(HttpRequest.newBuilder(uri("/api/auth/register"))
			.header("Content-Type", "application/json")
			.header("Cookie", "XSRF-TOKEN=" + token)
			.header("X-XSRF-TOKEN", token)
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"email": "ada@example.com", "password": "correct horse battery", "displayName": "Ada",
					 "organizationName": "Arena"}"""))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(registered.statusCode()).isEqualTo(201);
		assertThat(cookie(registered, "SESSION")).contains("HttpOnly").contains("SameSite=Lax");
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	private static String cookie(HttpResponse<?> response, String name) {
		List<String> cookies = response.headers().allValues("Set-Cookie");
		return cookies.stream()
			.filter((cookie) -> cookie.startsWith(name + "="))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No " + name + " cookie in " + cookies));
	}

}
