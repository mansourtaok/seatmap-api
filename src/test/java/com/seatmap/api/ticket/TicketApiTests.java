package com.seatmap.api.ticket;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.seatmap.api.TestAccounts;
import com.seatmap.api.TestAccounts.Staff;
import com.seatmap.api.account.SeatmapUser;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tickets for an on-sale event at a stand (seats 7 and 9, VIP) with a five-place standing
 * floor (GA). Alice is the organization's admin; Sam is its door staff (SCANNER).
 */
@SpringBootTest(properties = "seatmap.tickets.mail.directory=build/test-mail")
class TicketApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path MAIL = Path.of("build/test-mail");

	private static final String SEAT_7 = "North Stand · Block N1 · Row 3 · Seat 7";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EmailSender emailSender;

	@Autowired
	private TicketService tickets;

	@Autowired
	private TicketCodes codes;

	@MockitoSpyBean
	private MailDelivery mailDelivery;

	private MockMvc alice;

	private MockMvc sam;

	private MockMvc guest;

	private SeatmapUser aliceUser;

	private String eventId;

	@BeforeEach
	void putAnEventOnSale() throws Exception {
		TestAccounts.emptyDatabase(this.jdbc);
		FileSystemUtils.deleteRecursively(MAIL);
		reset(this.mailDelivery);
		Staff admin = TestAccounts.staff(this.jdbc, "Alice", "ADMIN");
		this.aliceUser = admin.user();
		this.alice = TestAccounts.mockMvcAs(this.context, admin.user());
		this.sam = TestAccounts.mockMvcAs(this.context,
				TestAccounts.join(this.jdbc, TestAccounts.user(this.jdbc, "Sam"), admin.organizationId(), "SCANNER")
					.user());
		this.guest = TestAccounts.guestMockMvc(this.context);
		this.eventId = createEventOnSale();
	}

	@Test
	void issuesOneTicketPerPlaceAndEmailsThemAsAPdf() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}",
				"{\"unitId\": \"standing-ga\", \"quantity\": 3}");
		this.guest.perform(put("/api/reservations/" + reservation + "/buyer").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Ada Lovelace\", \"email\": \"ada@example.com\"}")).andExpect(status().isOk());
		confirm(reservation);

		byte[] pdf = this.guest.perform(get("/api/reservations/" + reservation + "/tickets.pdf"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(4);
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Final", "Saturday 1 May 2027, 20:00 (Europe/Paris)", SEAT_7,
					"General Floor · Place 3 of 3", "Ada Lovelace", "120.50 EUR", "Ticket 4 of 4");
		}

		assertThat(this.emailSender.sendDue()).isEqualTo(1);
		MimeMessage email = onlyEmail();
		assertThat(email.getAllRecipients()[0]).hasToString("ada@example.com");
		assertThat(email.getSubject()).isEqualTo("Your tickets for Final");
		Multipart parts = (Multipart) email.getContent();
		assertThat(parts.getBodyPart(parts.getCount() - 1).getFileName()).isEqualTo("tickets.pdf");
		assertThat(text(email)).contains("Hello Ada Lovelace,",
				"Your 4 tickets for Final on Saturday 1 May 2027 at 20:00 (Europe/Paris) are attached.");
		assertThat(this.emailSender.sendDue()).isZero();
	}

	@Test
	void sellsAtTheBoxOfficeWithoutAnEmail() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		this.guest.perform(get("/api/reservations/" + reservation + "/tickets.pdf")).andExpect(status().isConflict());
		confirm(reservation);
		try (PDDocument document = Loader.loadPDF(pdf(reservation))) {
			assertThat(new PDFTextStripper().getText(document)).contains("Box office sale");
		}
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM email_outbox", Integer.class)).isZero();
	}

	@Test
	void letsTheDoorInOncePerTicket() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		confirm(reservation);
		String code = qrCodeOnPage(pdf(reservation), 0);

		checkIn(this.sam, code).andExpect(jsonPath("$.result").value("VALID"))
			.andExpect(jsonPath("$.label").value(SEAT_7))
			.andExpect(jsonPath("$.categoryName").value("VIP"));
		checkIn(this.sam, code).andExpect(jsonPath("$.result").value("ALREADY_CHECKED_IN"))
			.andExpect(jsonPath("$.checkedInAt").exists());
		this.sam.perform(get("/api/events/" + this.eventId + "/check-in/stats"))
			.andExpect(jsonPath("$.issued").value(1))
			.andExpect(jsonPath("$.checkedIn").value(1));

		String ticketId = this.jdbc.queryForObject("SELECT id::text FROM ticket", String.class);
		this.sam.perform(delete("/api/events/" + this.eventId + "/check-in/" + ticketId))
			.andExpect(status().isForbidden());
		this.alice.perform(delete("/api/events/" + this.eventId + "/check-in/" + ticketId))
			.andExpect(status().isNoContent());
		checkIn(this.sam, code).andExpect(jsonPath("$.result").value("VALID"));
	}

	@Test
	void turnsAwayForgedTamperedAndOtherEventsTickets() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		confirm(reservation);
		String code = qrCodeOnPage(pdf(reservation), 0);

		checkIn(this.sam, code.substring(0, code.length() - 2) + "xx").andExpect(jsonPath("$.result").value("NOT_VALID"));
		checkIn(this.sam, "SM1.forged.code").andExpect(jsonPath("$.result").value("NOT_VALID"));
		checkIn(this.sam, this.codes.codeFor(UUID.randomUUID())).andExpect(jsonPath("$.result").value("NOT_VALID"));

		String otherEvent = createEventOnSale();
		checkIn(this.sam, otherEvent, code).andExpect(jsonPath("$.result").value("NOT_VALID"))
			.andExpect(jsonPath("$.label").doesNotExist());
	}

	@Test
	void letsOnlyOneOfManyGatesAdmitTheSameTicket() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		confirm(reservation);
		String code = qrCodeOnPage(pdf(reservation), 0);
		UUID event = UUID.fromString(this.eventId);
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<TicketViews.CheckInResult.Result>> gates = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			gates.add(() -> {
				SecurityContextHolder.getContext()
					.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(this.aliceUser, null,
							List.of()));
				start.await();
				return this.tickets.checkIn(event, code).result();
			});
		}
		try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
			List<Future<TicketViews.CheckInResult.Result>> results = gates.stream().map(executor::submit).toList();
			start.countDown();
			List<TicketViews.CheckInResult.Result> outcomes = new ArrayList<>();
			for (Future<TicketViews.CheckInResult.Result> result : results) {
				outcomes.add(result.get());
			}
			assertThat(outcomes).containsOnlyOnce(TicketViews.CheckInResult.Result.VALID)
				.filteredOn((outcome) -> outcome != TicketViews.CheckInResult.Result.VALID)
				.containsOnly(TicketViews.CheckInResult.Result.ALREADY_CHECKED_IN);
		}
	}

	@Test
	void givesDoorStaffNoMoreThanCheckingIn() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		this.sam.perform(post("/api/reservations/" + reservation + "/confirm")).andExpect(status().isForbidden());
		this.sam.perform(put("/api/events/" + this.eventId + "/prices").contentType(MediaType.APPLICATION_JSON)
			.content("{\"prices\": {\"cat-vip\": 1}}")).andExpect(status().isForbidden());
		this.sam.perform(get("/api/events/" + this.eventId)).andExpect(status().isOk());
		this.guest.perform(post("/api/events/" + this.eventId + "/check-in").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"x\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void letsTheBuyerChangeTheirDetailsOnlyWhileHolding() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		String buyer = "/api/reservations/" + reservation + "/buyer";
		this.guest.perform(put(buyer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Ada\", \"email\": \"not-an-email\"}")).andExpect(status().isBadRequest());
		this.guest.perform(put(buyer).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Ada\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.buyer.name").value("Ada"));
		confirm(reservation);
		this.guest.perform(put(buyer).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Eve\"}"))
			.andExpect(status().isConflict());
		this.alice.perform(put(buyer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Ada L.\", \"email\": \"ada@example.com\"}"))
			.andExpect(jsonPath("$.buyer.email").value("ada@example.com"));
	}

	@Test
	void resendsTicketsWithoutSayingWhetherThereWereAny() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		this.guest.perform(put("/api/reservations/" + reservation + "/buyer").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Ada\", \"email\": \"ada@example.com\"}"));
		confirm(reservation);
		this.jdbc.update("DELETE FROM email_outbox");

		resend("ADA@example.com", "10.8.0.1").andExpect(status().isAccepted());
		resend("nobody@example.com", "10.8.0.1").andExpect(status().isAccepted());
		assertThat(this.jdbc.queryForList("SELECT recipient FROM email_outbox", String.class))
			.containsExactly("ada@example.com");

		resend("ada@example.com", "10.8.0.2").andExpect(status().isAccepted());
		resend("ada@example.com", "10.8.0.3").andExpect(status().isAccepted());
		resend("ada@example.com", "10.8.0.4").andExpect(status().isTooManyRequests());
	}

	@Test
	void retriesEmailsThatFailToSend() throws Exception {
		String reservation = hold("{\"unitId\": \"seat-n1\", \"quantity\": 1}");
		this.guest.perform(put("/api/reservations/" + reservation + "/buyer").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Ada\", \"email\": \"ada@example.com\"}"));
		confirm(reservation);
		doThrow(new IOException("mail server down")).doCallRealMethod().when(this.mailDelivery).deliver(any());

		assertThat(this.emailSender.sendDue()).isEqualTo(1);
		assertThat(this.jdbc.queryForMap("SELECT status, attempts, last_error FROM email_outbox"))
			.containsEntry("status", "PENDING")
			.containsEntry("attempts", 1)
			.containsEntry("last_error", "java.io.IOException: mail server down");
		assertThat(this.emailSender.sendDue()).as("waits before retrying").isZero();

		this.jdbc.update("UPDATE email_outbox SET next_attempt_at = now()");
		assertThat(this.emailSender.sendDue()).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("SENT");
		assertThat(onlyEmail().getSubject()).isEqualTo("Your tickets for Final");
	}

	private String createEventOnSale() throws Exception {
		String layoutId = read(this.alice
			.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON).content(layout().toString()))
			.andExpect(status().isCreated())).get("id").asString();
		this.alice.perform(post("/api/layouts/" + layoutId + "/versions")).andExpect(status().isCreated());
		String event = read(this.alice.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
				{"layoutId": "%s", "version": 1, "name": "Final", "startsAt": "2027-05-01T18:00:00Z",
				 "currency": "EUR", "timeZone": "Europe/Paris"}""".formatted(layoutId)))
			.andExpect(status().isCreated())).get("id").asString();
		this.alice.perform(put("/api/events/" + event).header(HttpHeaders.IF_MATCH, "\"0\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Final", "startsAt": "2027-05-01T18:00:00Z", "currency": "EUR",
					 "timeZone": "Europe/Paris", "status": "ON_SALE"}"""))
			.andExpect(status().isOk());
		return event;
	}

	private String hold(String... items) throws Exception {
		return read(this.alice.perform(post("/api/events/" + this.eventId + "/reservations")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\": [" + String.join(", ", items) + "]}")).andExpect(status().isCreated())).get("id")
			.asString();
	}

	private void confirm(String reservation) throws Exception {
		this.alice.perform(post("/api/reservations/" + reservation + "/confirm"))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	private byte[] pdf(String reservation) throws Exception {
		return this.guest.perform(get("/api/reservations/" + reservation + "/tickets.pdf"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
	}

	private ResultActions checkIn(MockMvc mvc, String code) throws Exception {
		return checkIn(mvc, this.eventId, code);
	}

	private ResultActions checkIn(MockMvc mvc, String event, String code) throws Exception {
		return mvc.perform(post("/api/events/" + event + "/check-in").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"%s\"}".formatted(code))).andExpect(status().isOk());
	}

	private ResultActions resend(String email, String clientIp) throws Exception {
		return this.guest.perform(post("/api/public/tickets/resend").with((request) -> {
			request.setRemoteAddr(clientIp);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"%s\"}".formatted(email)));
	}

	/** Renders the page as a phone camera would see it and reads its QR code. */
	private static String qrCodeOnPage(byte[] pdf, int page) throws Exception {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page, 150);
			return new QRCodeReader()
				.decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
				.getText();
		}
	}

	/** The plain-text body, wherever the multipart nesting put it. */
	private static String text(Part part) throws Exception {
		if (part.isMimeType("text/plain")) {
			return part.getContent().toString();
		}
		if (part.getContent() instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				String text = text(multipart.getBodyPart(i));
				if (text != null) {
					return text;
				}
			}
		}
		return null;
	}

	private static MimeMessage onlyEmail() throws Exception {
		try (Stream<Path> files = Files.list(MAIL)) {
			List<Path> emails = files.toList();
			assertThat(emails).hasSize(1);
			try (InputStream in = Files.newInputStream(emails.getFirst())) {
				return new MimeMessage(Session.getInstance(new java.util.Properties()), in);
			}
		}
	}

	private static JsonNode read(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static ObjectNode layout() throws Exception {
		ObjectNode document = (ObjectNode) JSON
			.readTree(TicketApiTests.class.getResourceAsStream("/layouts/conference-standing.json"));
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
			.add("seat-n3");
		for (String[] seat : new String[][] { { "seat-n1", "7" }, { "seat-n3", "9" } }) {
			objects.addObject()
				.put("id", seat[0])
				.put("type", "SEAT")
				.put("parentId", "stand-n")
				.put("name", "Seat " + seat[1])
				.put("seatNumber", seat[1])
				.put("block", "N1")
				.put("row", "3")
				.put("ticketCategoryId", "cat-vip")
				.put("x", 9)
				.put("y", 2.25);
		}
		objects.addObject()
			.put("id", "standing-ga")
			.put("type", "STANDING_AREA")
			.put("name", "General Floor")
			.put("capacity", 5)
			.put("ticketCategoryId", "cat-ga")
			.put("x", 10)
			.put("y", 10);
		return document;
	}

}
