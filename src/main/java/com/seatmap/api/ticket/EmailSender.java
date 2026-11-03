package com.seatmap.api.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Sends what's due in the outbox, one email per transaction, every few seconds. */
@Component
class EmailSender {

	private static final Logger logger = LoggerFactory.getLogger(EmailSender.class);

	private static final int BATCH = 50;

	private final EmailOutbox outbox;

	private final TicketRepository tickets;

	private final TicketPdf pdf;

	private final MailDelivery delivery;

	private final TicketProperties properties;

	private final TransactionTemplate transaction;

	EmailSender(EmailOutbox outbox, TicketRepository tickets, TicketPdf pdf, MailDelivery delivery,
			TicketProperties properties, TransactionTemplate transaction) {
		this.outbox = outbox;
		this.tickets = tickets;
		this.pdf = pdf;
		this.delivery = delivery;
		this.properties = properties;
		this.transaction = transaction;
	}

	/** @return how many emails it tried to send */
	@Scheduled(fixedDelayString = "${seatmap.tickets.mail.send-interval:15s}",
			initialDelayString = "${seatmap.tickets.mail.send-interval:15s}")
	int sendDue() {
		int tried = 0;
		while (tried < BATCH && Boolean.TRUE.equals(this.transaction.execute((status) -> sendNext()))) {
			tried++;
		}
		return tried;
	}

	private boolean sendNext() {
		Instant now = Instant.now();
		Optional<EmailOutbox.Email> next = this.outbox.claimNext(now);
		if (next.isEmpty()) {
			return false;
		}
		EmailOutbox.Email email = next.get();
		try {
			this.delivery.deliver(message(email));
			this.outbox.markSent(email.id(), now);
		}
		catch (Exception ex) {
			logger.warn("Couldn't send email {} to {}: {}", email.id(), email.recipient(), ex.toString());
			this.outbox.markFailed(email, ex.toString(), now);
		}
		return true;
	}

	private MimeMessage message(EmailOutbox.Email email) throws Exception {
		MimeMessage message = new MimeMessage(Session.getInstance(new java.util.Properties()));
		MimeMessageHelper helper = new MimeMessageHelper(message, email.reservationId() != null, "UTF-8");
		helper.setFrom(this.properties.mail().from());
		helper.setTo(email.recipient());
		helper.setSubject(email.subject());
		helper.setText(email.body());
		if (email.reservationId() != null) {
			TicketRepository.Order order = this.tickets.findOrder(email.reservationId()).orElseThrow();
			List<TicketRepository.Ticket> issued = this.tickets.findByReservation(email.reservationId());
			helper.addAttachment("tickets.pdf", new ByteArrayResource(this.pdf.render(order, issued)),
					"application/pdf");
		}
		message.saveChanges();
		return message;
	}

}
