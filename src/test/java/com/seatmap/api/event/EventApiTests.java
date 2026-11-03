package com.seatmap.api.event;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.seatmap.api.TestAccounts;
import com.seatmap.api.web.ConflictException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Events against a published layout with a stand (seat 7 VIP, seat 8 blocked, seat 9 VIP)
 * and a five-place standing area (GA).
 */
@SpringBootTest
class EventApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SEAT_7 = "North Stand · Block N1 · Row 3 · Seat 7";

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ReservationService reservations;

	private String eventId;

	@BeforeEach
	void createEvent() throws Exception {
		TestAccounts.emptyDatabase(this.jdbc);
		this.mvc = TestAccounts.mockMvcAs(this.context, TestAccounts.staff(this.jdbc, "Alice", "ADMIN").user());
		String layoutId = read(this.mvc
			.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON).content(layout().toString()))
			.andExpect(status().isCreated())).get("id").asString();
		this.mvc.perform(post("/api/layouts/" + layoutId + "/versions")).andExpect(status().isCreated());
		this.eventId = read(this.mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
				{"layoutId": "%s", "version": 1, "name": "Final", "startsAt": "2027-05-01T18:00:00Z",
				 "endsAt": "2027-05-01T21:00:00Z", "currency": "EUR", "timeZone": "Europe/Paris"}""".formatted(layoutId)))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.prices[*].categoryId").value(contains("cat-ga", "cat-vip")))
			.andExpect(jsonPath("$.prices[1].price").value(120.5))).get("id").asString();
	}

	@Test
	void sellsOnlyWhileOnSale() throws Exception {
		hold("seat-n1").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Tickets for this event aren't on sale"));
		putOnSale();
		hold("seat-n1").andExpect(status().isCreated());
	}

	@Test
	void updatesFromTheCurrentRevisionAndAllowedStatusesOnly() throws Exception {
		putOnSale();
		update("0", "DRAFT").andExpect(status().isConflict());
		update("1", "CANCELLED").andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
		update("2", "ON_SALE").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("An event that is CANCELLED can't become ON_SALE"));
	}

	@Test
	void holdsThenSellsSeatsAndStandingPlaces() throws Exception {
		putOnSale();
		JsonNode reservation = read(postJson("/api/events/" + this.eventId + "/reservations", """
				{"items": [{"unitId": "seat-n1", "quantity": 1}, {"unitId": "standing-ga", "quantity": 2}]}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("HELD"))
			.andExpect(jsonPath("$.total").value(180.5))
			.andExpect(jsonPath("$.currency").value("EUR"))
			.andExpect(jsonPath("$.items[0].label").value(SEAT_7)));
		String reservationId = reservation.get("id").asString();

		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses"))
			.andExpect(jsonPath("$['seat-n1']").value("held"))
			.andExpect(jsonPath("$['seat-n2']").value("blocked"))
			.andExpect(jsonPath("$['seat-n3']").doesNotExist());
		this.mvc.perform(get("/api/events/" + this.eventId + "/availability"))
			.andExpect(jsonPath("$.categories[1].capacity").value(2))
			.andExpect(jsonPath("$.categories[1].held").value(1))
			.andExpect(jsonPath("$.categories[1].available").value(1))
			.andExpect(jsonPath("$.areas[0].held").value(2))
			.andExpect(jsonPath("$.areas[0].available").value(3))
			.andExpect(jsonPath("$.withoutCategory").value(1));

		this.mvc.perform(post("/api/reservations/" + reservationId + "/confirm"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
		this.mvc.perform(post("/api/reservations/" + reservationId + "/confirm"))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses"))
			.andExpect(jsonPath("$['seat-n1']").value("sold"));
		this.mvc.perform(get("/api/events/" + this.eventId + "/availability"))
			.andExpect(jsonPath("$.categories[0].sold").value(2))
			.andExpect(jsonPath("$.categories[1].sold").value(1));
		this.mvc.perform(delete("/api/reservations/" + reservationId)).andExpect(status().isConflict());
	}

	@Test
	void holdsAllOrNothing() throws Exception {
		putOnSale();
		hold("seat-n1").andExpect(status().isCreated());
		postJson("/api/events/" + this.eventId + "/reservations", """
				{"items": [{"unitId": "seat-n3", "quantity": 1}, {"unitId": "seat-n1", "quantity": 1}]}""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.problems").value(contains(SEAT_7 + " is taken")));
		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses"))
			.andExpect(jsonPath("$['seat-n3']").doesNotExist());
	}

	@Test
	void refusesWhatIsNotForSaleOrNotInTheEvent() throws Exception {
		putOnSale();
		hold("seat-n2").andExpect(status().isConflict())
			.andExpect(jsonPath("$.problems").value(contains("North Stand · Block N1 · Row 3 · Seat 8 isn't for sale")));
		postJson("/api/events/" + this.eventId + "/reservations", """
				{"items": [{"unitId": "seat-n1", "quantity": 2}, {"unitId": "nowhere", "quantity": 1}]}""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.problems").value(
					contains("'nowhere' isn't sold at this event", SEAT_7 + " is a seat, so its quantity must be 1")));
		postJson("/api/events/" + this.eventId + "/reservations", "{\"items\": []}").andExpect(status().isBadRequest());
	}

	@Test
	void sellsNoMoreStandingPlacesThanTheAreaHolds() throws Exception {
		putOnSale();
		holdStanding(4).andExpect(status().isCreated());
		holdStanding(2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.problems").value(contains("General Floor has only 1 left")));
		holdStanding(1).andExpect(status().isCreated());
	}

	@Test
	void releasingAHoldFreesItsSeats() throws Exception {
		putOnSale();
		String reservationId = read(hold("seat-n1")).get("id").asString();
		this.mvc.perform(delete("/api/reservations/" + reservationId)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/reservations/" + reservationId)).andExpect(jsonPath("$.status").value("RELEASED"));
		hold("seat-n1").andExpect(status().isCreated());
	}

	@Test
	void expiredHoldsGoBackOnSale() throws Exception {
		putOnSale();
		String expired = read(hold("seat-n1")).get("id").asString();
		this.jdbc.update("UPDATE reservation SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", expired);
		this.jdbc.update("UPDATE event_seat SET expires_at = now() - interval '1 minute' WHERE reservation_id = ?::uuid",
				expired);

		this.mvc.perform(get("/api/reservations/" + expired)).andExpect(jsonPath("$.status").value("RELEASED"));
		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses"))
			.andExpect(jsonPath("$['seat-n1']").doesNotExist());
		hold("seat-n1").andExpect(status().isCreated());
		this.mvc.perform(post("/api/reservations/" + expired + "/confirm")).andExpect(status().isConflict());

		this.reservations.releaseExpired();
		assertThat(this.jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?::uuid", String.class, expired))
			.isEqualTo("RELEASED");
		this.mvc.perform(get("/api/events/" + this.eventId + "/seat-statuses"))
			.andExpect(jsonPath("$['seat-n1']").value("held"));
	}

	@Test
	void keepsTheHeldPriceWhenPricesChange() throws Exception {
		putOnSale();
		hold("seat-n1").andExpect(jsonPath("$.items[0].unitPrice").value(120.5));
		putJson("/api/events/" + this.eventId + "/prices", "{\"prices\": {\"cat-vip\": 99}}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.prices[1].price").value(99));
		hold("seat-n3").andExpect(jsonPath("$.total").value(99));
		putJson("/api/events/" + this.eventId + "/prices", "{\"prices\": {\"cat-nope\": 1}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.problems", hasItem("Unknown ticket category 'cat-nope'")));
	}

	@Test
	void letsOnlyOneOfManySimultaneousBuyersHoldASeat() throws Exception {
		putOnSale();
		assertThat(raceFor("seat-n3", 1, 20)).isEqualTo(1);
	}

	@Test
	void letsSimultaneousBuyersTakeOnlyTheStandingPlacesThereAre() throws Exception {
		putOnSale();
		assertThat(raceFor("standing-ga", 1, 12)).isEqualTo(5);
	}

	/** Starts {@code buyers} holds of the same unit at once; returns how many succeeded. */
	private int raceFor(String unitId, int quantity, int buyers) throws Exception {
		UUID event = UUID.fromString(this.eventId);
		EventRequests.Hold request = new EventRequests.Hold(List.of(new EventRequests.Hold.Item(unitId, quantity)));
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < buyers; i++) {
			// Guests, each from their own address so the per-guest limits don't decide the race.
			String clientIp = "10.0.0." + i;
			attempts.add(() -> {
				start.await();
				try {
					this.reservations.hold(event, request, clientIp);
					return true;
				}
				catch (ConflictException ex) {
					return false;
				}
			});
		}
		try (ExecutorService executor = Executors.newFixedThreadPool(buyers)) {
			List<Future<Boolean>> results = attempts.stream().map(executor::submit).toList();
			start.countDown();
			int successes = 0;
			for (Future<Boolean> result : results) {
				successes += result.get() ? 1 : 0;
			}
			return successes;
		}
	}

	private void putOnSale() throws Exception {
		update("0", "ON_SALE").andExpect(status().isOk());
	}

	private ResultActions update(String revision, String status) throws Exception {
		return this.mvc.perform(put("/api/events/" + this.eventId).header(HttpHeaders.IF_MATCH, "\"" + revision + "\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Final", "startsAt": "2027-05-01T18:00:00Z", "endsAt": "2027-05-01T21:00:00Z",
					 "currency": "EUR", "timeZone": "Europe/Paris", "status": "%s"}""".formatted(status)));
	}

	private ResultActions hold(String seatId) throws Exception {
		return postJson("/api/events/" + this.eventId + "/reservations",
				"{\"items\": [{\"unitId\": \"%s\", \"quantity\": 1}]}".formatted(seatId));
	}

	private ResultActions holdStanding(int quantity) throws Exception {
		return postJson("/api/events/" + this.eventId + "/reservations",
				"{\"items\": [{\"unitId\": \"standing-ga\", \"quantity\": %d}]}".formatted(quantity));
	}

	private ResultActions postJson(String path, String body) throws Exception {
		return this.mvc.perform(post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions putJson(String path, String body) throws Exception {
		return this.mvc.perform(put(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static JsonNode read(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	/** The stand from the layout tests, plus a third seat and a categorised standing area. */
	private static ObjectNode layout() throws Exception {
		ObjectNode document = (ObjectNode) JSON
			.readTree(EventApiTests.class.getResourceAsStream("/layouts/conference-standing.json"));
		document.put("name", "Arena");
		ArrayNode categories = document.putArray("ticketCategories");
		categories.addObject().put("id", "cat-vip").put("name", "VIP").put("color", "#f472b6").put("price", 120.5);
		categories.addObject().put("id", "cat-ga").put("name", "GA").put("color", "#60a5fa").put("price", 30);
		ArrayNode objects = document.putArray("objects");
		objects.addObject()
			.put("id", "stand-n")
			.put("type", "STAND")
			.put("name", "North Stand")
			.put("x", 10)
			.put("y", 2)
			.putArray("seatIds")
			.add("seat-n1")
			.add("seat-n2")
			.add("seat-n3");
		seat(objects, "seat-n1", "7").put("ticketCategoryId", "cat-vip");
		seat(objects, "seat-n2", "8").put("status", "blocked");
		seat(objects, "seat-n3", "9").put("ticketCategoryId", "cat-vip");
		objects.addObject()
			.put("id", "standing-ga")
			.put("type", "STANDING_AREA")
			.put("name", "General Floor")
			.put("capacity", 5)
			.put("ticketCategoryId", "cat-ga")
			.put("x", 10)
			.put("y", 10)
			.putObject("boundary")
			.put("kind", "rect")
			.put("width", 8)
			.put("height", 4);
		return document;
	}

	private static ObjectNode seat(ArrayNode objects, String id, String number) {
		return objects.addObject()
			.put("id", id)
			.put("type", "SEAT")
			.put("parentId", "stand-n")
			.put("name", "Seat " + number)
			.put("seatNumber", number)
			.put("block", "N1")
			.put("row", "3")
			.put("x", 9 + Integer.parseInt(number) * 0.1)
			.put("y", 2.25);
	}

}
