package com.seatmap.api.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seatmap.api.TestAccounts;
import com.seatmap.api.layout.TestDocuments;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The build switches the reset on for tests (build.gradle.kts), so this shares the usual context. */
@SpringBootTest
class DevDataApiTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mvc;

	@BeforeEach
	void logIn() {
		TestAccounts.emptyDatabase(this.jdbc);
		this.mvc = TestAccounts.mockMvcAs(this.context, TestAccounts.staff(this.jdbc, "Alice", "ADMIN").user());
	}

	@Test
	void wipesLayoutsVersionsAndInventoryButKeepsAccounts() throws Exception {
		String location = this.mvc
			.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON)
				.content(TestDocuments.load("small-theater").toString()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		this.mvc.perform(post(location + "/versions")).andExpect(status().isCreated());
		assertThat(count("inventory_unit")).isPositive();

		this.mvc.perform(delete("/api/dev/data")).andExpect(status().isNoContent());

		for (String table : DevDataController.TABLES) {
			assertThat(count(table)).as(table).isZero();
		}
		assertThat(count("app_user")).isEqualTo(1);
		assertThat(count("organization")).isEqualTo(1);
	}

	@Test
	void isOnlyThereWhenSwitchedOn() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withUserConfiguration(DevDataController.class);
		runner.withPropertyValues("seatmap.dev.data-reset-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(DevDataController.class));
		runner.withPropertyValues("seatmap.dev.data-reset-enabled=true")
			.run((context) -> assertThat(context).hasSingleBean(DevDataController.class));
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
