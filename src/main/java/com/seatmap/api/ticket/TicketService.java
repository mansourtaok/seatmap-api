package com.seatmap.api.ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.seatmap.api.account.Access;
import com.seatmap.api.account.Role;
import com.seatmap.api.account.SeatmapUser;
import com.seatmap.api.ticket.TicketViews.CheckInResult;
import com.seatmap.api.ticket.TicketViews.CheckInResult.Result;
import com.seatmap.api.web.ConflictException;
import com.seatmap.api.web.NotFoundException;
import com.seatmap.api.web.RateLimiter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Issuing tickets when a sale is confirmed, delivering them, and checking them in at the door. */
@Service
public class TicketService {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm",
			Locale.ENGLISH);

	/** Most past orders one resend request sends again. */
	private static final int RESEND_ORDERS = 10;

	private final TicketRepository tickets;

	private final TicketCodes codes;

	private final TicketPdf pdf;

	private final EmailOutbox outbox;

	private final Access access;

	private final RateLimiter resendsPerClient = new RateLimiter(5, Duration.ofHours(1));

	private final RateLimiter resendsPerEmail = new RateLimiter(3, Duration.ofHours(1));

	TicketService(TicketRepository tickets, TicketCodes codes, TicketPdf pdf, EmailOutbox outbox, Access access) {
		this.tickets = tickets;
		this.codes = codes;
		this.pdf = pdf;
		this.outbox = outbox;
		this.access = access;
	}

	/**
	 * Issues a just-confirmed reservation's tickets and, if the buyer gave an email, queues
	 * them to it. Runs inside the confirmation, so tickets and email exist exactly when the
	 * sale does.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void issue(UUID reservationId) {
		Instant now = now();
		this.tickets.issue(reservationId, now);
		TicketRepository.Order order = this.tickets.findOrder(reservationId).orElseThrow();
		if (order.buyerEmail() != null) {
			queue(order, now);
		}
	}

	/** The PDF of a confirmed reservation's tickets. The reservation id is the buyer's key to it. */
	@Transactional(readOnly = true)
	public byte[] pdf(UUID reservationId) {
		TicketRepository.Order order = this.tickets.findOrder(reservationId)
			.orElseThrow(() -> new NotFoundException("No reservation " + reservationId));
		if (!"CONFIRMED".equals(order.status())) {
			throw new ConflictException("Tickets are issued once the reservation is confirmed");
		}
		return this.pdf.render(order, this.tickets.findByReservation(reservationId));
	}

	/**
	 * Sends the tickets of every recent order made with this email to it again. Says nothing
	 * about whether there were any, so nobody can find out who bought tickets.
	 */
	@Transactional
	public void resend(String email, String clientIp) {
		this.resendsPerClient.acquire(clientIp, "Too many requests; try again later");
		this.resendsPerEmail.acquire(email.strip().toLowerCase(Locale.ROOT), "Too many requests; try again later");
		Instant now = now();
		for (UUID reservationId : this.tickets.findConfirmedByEmail(email, RESEND_ORDERS)) {
			this.tickets.findOrder(reservationId).ifPresent((order) -> queue(order, now));
		}
	}

	/** Checks the ticket in a scanned code in at the event. Door staff (SCANNER) and up. */
	@Transactional
	public CheckInResult checkIn(UUID eventId, String code) {
		requireRole(eventId, Role.SCANNER);
		SeatmapUser scanner = this.access.requireUser();
		Optional<UUID> ticketId = this.codes.verify(code);
		if (ticketId.isEmpty()) {
			return new CheckInResult(Result.NOT_VALID, null, null, null, null);
		}
		Optional<TicketRepository.CheckIn> checkedIn = this.tickets.checkIn(ticketId.get(), eventId, scanner.id(),
				now());
		if (checkedIn.isPresent()) {
			return result(Result.VALID, checkedIn.get());
		}
		return this.tickets.findCheckIn(ticketId.get(), eventId)
			.map((earlier) -> result(Result.ALREADY_CHECKED_IN, earlier))
			// Genuine but for another event: the scanner shouldn't learn which.
			.orElseGet(() -> new CheckInResult(Result.NOT_VALID, null, null, null, null));
	}

	/** Undoes a mistaken check-in; editors and up, so door staff can't let a ticket in twice. */
	@Transactional
	public void undoCheckIn(UUID eventId, UUID ticketId) {
		requireRole(eventId, Role.EDITOR);
		if (!this.tickets.undoCheckIn(ticketId, eventId)) {
			throw new NotFoundException("This ticket isn't checked in at this event");
		}
	}

	@Transactional(readOnly = true)
	public TicketViews.CheckInStats checkInStats(UUID eventId) {
		requireRole(eventId, Role.SCANNER);
		List<TicketViews.CategoryCheckIns> categories = this.tickets.countCheckIns(eventId);
		return new TicketViews.CheckInStats(categories.stream().mapToInt(TicketViews.CategoryCheckIns::issued).sum(),
				categories.stream().mapToInt(TicketViews.CategoryCheckIns::checkedIn).sum(), categories);
	}

	private void queue(TicketRepository.Order order, Instant now) {
		int count = this.tickets.findByReservation(order.reservationId()).size();
		String greeting = (order.buyerName() != null) ? "Hello " + order.buyerName() + "," : "Hello,";
		String body = """
				%s

				Your %s for %s on %s (%s) %s attached.

				Show the QR code on each ticket at the entrance, on your phone or printed. Each ticket lets one \
				person in, once.

				Order %s

				%s
				""".formatted(greeting, (count == 1) ? "ticket" : count + " tickets", order.eventName(),
				WHEN.format(order.startsAt().atZone(order.timeZone())), order.timeZone().getId(),
				(count == 1) ? "is" : "are", order.reservationId(), order.organizationName());
		this.outbox.enqueue(order.buyerEmail(), "Your tickets for " + order.eventName(), body, order.reservationId(),
				now);
	}

	private void requireRole(UUID eventId, Role role) {
		UUID organization = this.tickets.findEventOrganization(eventId).orElseThrow(() -> eventNotFound(eventId));
		this.access.require(organization, role, () -> eventNotFound(eventId));
	}

	private static CheckInResult result(Result result, TicketRepository.CheckIn checkIn) {
		return new CheckInResult(result, checkIn.ticketId(), checkIn.label(), checkIn.categoryName(),
				checkIn.checkedInAt());
	}

	private static NotFoundException eventNotFound(UUID id) {
		return new NotFoundException("No event " + id);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
