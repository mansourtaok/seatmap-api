package com.seatmap.api.ticket;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ticket PDFs, resending them, and checking tickets in at the door. */
@RestController
class TicketController {

	private final TicketService tickets;

	TicketController(TicketService tickets) {
		this.tickets = tickets;
	}

	@GetMapping("/api/reservations/{id}/tickets.pdf")
	ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("tickets-" + id + ".pdf").build().toString())
			// The PDF holds valid tickets: keep it out of shared caches.
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.body(this.tickets.pdf(id));
	}

	/** Always 202, whether or not the email has orders. */
	@PostMapping("/api/public/tickets/resend")
	ResponseEntity<Void> resend(@Valid @RequestBody TicketViews.ResendRequest request,
			HttpServletRequest servletRequest) {
		this.tickets.resend(request.email(), servletRequest.getRemoteAddr());
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/api/events/{id}/check-in")
	TicketViews.CheckInResult checkIn(@PathVariable UUID id, @Valid @RequestBody TicketViews.CheckInRequest request) {
		return this.tickets.checkIn(id, request.code());
	}

	@DeleteMapping("/api/events/{id}/check-in/{ticketId}")
	ResponseEntity<Void> undoCheckIn(@PathVariable UUID id, @PathVariable UUID ticketId) {
		this.tickets.undoCheckIn(id, ticketId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/events/{id}/check-in/stats")
	TicketViews.CheckInStats checkInStats(@PathVariable UUID id) {
		return this.tickets.checkInStats(id);
	}

}
