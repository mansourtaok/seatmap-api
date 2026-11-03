package com.seatmap.api.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.seatmap.api.TestAccounts;
import com.seatmap.api.TestAccounts.Staff;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may do what: Alice is admin of the organization that owns the layout and event, Bob
 * is admin of another one, and guests aren't logged in.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private Staff alice;

	private Staff bob;

	private String layoutId;

	private String eventId;

	@BeforeEach
	void createLayoutAndEvent() throws Exception {
		TestAccounts.emptyDatabase(this.jdbc);
		this.alice = TestAccounts.staff(this.jdbc, "Alice", "ADMIN");
		this.bob = TestAccounts.staff(this.jdbc, "Bob", "ADMIN");
		this.layoutId = read(perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON)
			.content(layout().toString()), this.alice).andExpect(status().isCreated())).get("id").asString();
		perform(post("/api/layouts/" + this.layoutId + "/versions"), this.alice).andExpect(status().isCreated());
		this.eventId = read(perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
				{"layoutId": "%s", "version": 1, "name": "Gala", "startsAt": "2027-05-01T18:00:00Z", "currency": "EUR"}"""
			.formatted(this.layoutId)), this.alice).andExpect(status().isCreated())).get("id").asString();
	}

	@Test
	void hidesOtherOrganizationsLayoutsAndEvents() throws Exception {
		perform(get("/api/layouts/" + this.layoutId), this.bob).andExpect(status().isNotFound());
		perform(get("/api/events/" + this.eventId), this.bob).andExpect(status().isNotFound());
		perform(get("/api/layouts"), this.bob).andExpect(jsonPath("$.length()").value(0));
		perform(get("/api/layouts"), this.alice).andExpect(jsonPath("$.length()").value(1));
		perform(post("/api/layouts").param("organizationId", this.alice.organizationId().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(layout().toString()), this.bob).andExpect(status().isNotFound());
	}

	@Test
	void appliesRolesAndTheirChangesAtOnce() throws Exception {
		String members = "/api/organizations/" + this.alice.organizationId() + "/members";
		perform(post(members).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"role\": \"VIEWER\"}".formatted(this.bob.user().email())), this.alice)
			.andExpect(status().isCreated());

		perform(get("/api/layouts/" + this.layoutId), this.bob).andExpect(status().isOk());
		perform(post("/api/layouts/" + this.layoutId + "/versions"), this.bob).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.detail").value("This needs the EDITOR role in the organization; you are VIEWER"));
		perform(get(members), this.bob).andExpect(jsonPath("$.length()").value(2));
		perform(post(members).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"role\": \"VIEWER\"}".formatted(this.alice.user().email())), this.bob)
			.andExpect(status().isForbidden());

		perform(put(members + "/" + this.bob.user().id()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"role\": \"EDITOR\"}"), this.alice).andExpect(status().isNoContent());
		perform(post("/api/layouts/" + this.layoutId + "/versions"), this.bob).andExpect(status().isCreated());
		perform(delete("/api/layouts/" + this.layoutId), this.bob).andExpect(status().isForbidden());

		perform(delete(members + "/" + this.bob.user().id()), this.bob).andExpect(status().isNoContent());
		perform(get("/api/layouts/" + this.layoutId), this.bob).andExpect(status().isNotFound());
	}

	@Test
	void keepsAtLeastOneAdmin() throws Exception {
		String alicesMembership = "/api/organizations/" + this.alice.organizationId() + "/members/"
				+ this.alice.user().id();
		perform(put(alicesMembership).contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"EDITOR\"}"),
				this.alice)
			.andExpect(status().isConflict());
		perform(delete(alicesMembership), this.alice).andExpect(status().isConflict());
	}

	@Test
	void needsALoginForEverythingButTheBuyersPages() throws Exception {
		this.mvc.perform(get("/api/layouts")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/events/" + this.eventId)).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
	}

	@Test
	void showsGuestsOnlyEventsOnSale() throws Exception {
		this.mvc.perform(get("/api/public/events/" + this.eventId)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses")).andExpect(status().isNotFound());
		guestHold("seat-1", "10.9.0.1").andExpect(status().isNotFound());

		putOnSale();
		this.mvc.perform(get("/api/public/events"))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Gala"));
		this.mvc.perform(get("/api/public/events/" + this.eventId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.event.prices[0].price").value(40))
			.andExpect(jsonPath("$.layout.objects.length()").value(3));
		this.mvc.perform(get("/api/events/" + this.eventId + "/availability")).andExpect(status().isOk());
	}

	@Test
	void letsGuestsHoldAndReleaseButOnlyStaffConfirm() throws Exception {
		putOnSale();
		String reservation = read(guestHold("seat-1", "10.9.0.2").andExpect(status().isCreated())).get("id")
			.asString();
		this.mvc.perform(get("/api/reservations/" + reservation)).andExpect(jsonPath("$.status").value("HELD"));

		this.mvc.perform(post("/api/reservations/" + reservation + "/confirm").with(csrf()))
			.andExpect(status().isUnauthorized());
		perform(post("/api/reservations/" + reservation + "/confirm"), this.bob).andExpect(status().isNotFound());
		perform(post("/api/reservations/" + reservation + "/confirm"), this.alice)
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		String other = read(guestHold("seat-2", "10.9.0.2")).get("id").asString();
		this.mvc.perform(delete("/api/reservations/" + other).with(csrf())).andExpect(status().isNoContent());
	}

	@Test
	void limitsHowOftenAGuestCanHold() throws Exception {
		putOnSale();
		for (int i = 0; i < 10; i++) {
			String reservation = read(guestHold("seat-1", "10.9.0.3").andExpect(status().isCreated())).get("id")
				.asString();
			this.mvc.perform(delete("/api/reservations/" + reservation).with(csrf()));
		}
		guestHold("seat-1", "10.9.0.3").andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		guestHold("seat-1", "10.9.0.4").andExpect(status().isCreated());
	}

	@Test
	void limitsHowMuchAGuestCanHoldButNotTheBoxOffice() throws Exception {
		putOnSale();
		holdStanding(15, "10.9.0.5", null).andExpect(status().isCreated());
		holdStanding(6, "10.9.0.5", null).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.detail")
				.value("You can hold at most 20 places at once; buy or release the ones you hold first"));
		holdStanding(5, "10.9.0.5", null).andExpect(status().isCreated());
		holdStanding(30, "10.9.0.5", this.alice).andExpect(status().isCreated());
	}

	private void putOnSale() throws Exception {
		perform(put("/api/events/" + this.eventId).header(HttpHeaders.IF_MATCH, "\"0\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Gala", "startsAt": "2027-05-01T18:00:00Z", "currency": "EUR", "status": "ON_SALE"}"""),
				this.alice)
			.andExpect(status().isOk());
	}

	private ResultActions guestHold(String seatId, String clientIp) throws Exception {
		return this.mvc.perform(post("/api/events/" + this.eventId + "/reservations").with(csrf())
			.with(from(clientIp))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\": [{\"unitId\": \"%s\", \"quantity\": 1}]}".formatted(seatId)));
	}

	private ResultActions holdStanding(int quantity, String clientIp, Staff staff) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/events/" + this.eventId + "/reservations").with(csrf())
			.with(from(clientIp))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\": [{\"unitId\": \"floor\", \"quantity\": %d}]}".formatted(quantity));
		return (staff != null) ? perform(request, staff) : this.mvc.perform(request);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, Staff staff) throws Exception {
		return this.mvc.perform(request.with(TestAccounts.as(staff.user())).with(csrf()));
	}

	private static RequestPostProcessor from(String clientIp) {
		return (request) -> {
			request.setRemoteAddr(clientIp);
			return request;
		};
	}

	private static JsonNode read(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	/** Two seats and a 100-place standing floor, all in one category at 40. */
	private static ObjectNode layout() throws Exception {
		ObjectNode document = (ObjectNode) JSON
			.readTree(AccessApiTests.class.getResourceAsStream("/layouts/conference-standing.json"));
		ArrayNode categories = document.putArray("ticketCategories");
		categories.addObject().put("id", "cat").put("name", "Standard").put("color", "#60a5fa").put("price", 40);
		ArrayNode objects = document.putArray("objects");
		for (String id : new String[] { "seat-1", "seat-2" }) {
			objects.addObject()
				.put("id", id)
				.put("type", "SEAT")
				.put("name", id)
				.put("seatNumber", id.substring(5))
				.put("ticketCategoryId", "cat")
				.put("x", 1)
				.put("y", 1);
		}
		objects.addObject()
			.put("id", "floor")
			.put("type", "STANDING_AREA")
			.put("name", "Floor")
			.put("capacity", 100)
			.put("ticketCategoryId", "cat")
			.put("x", 10)
			.put("y", 10);
		return document;
	}

}
