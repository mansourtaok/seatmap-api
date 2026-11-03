package com.seatmap.api.ticket;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Emails waiting to be sent. Queuing one is part of the transaction that causes it, so it
 * goes out exactly when that commits; failed sends are retried with growing pauses.
 */
@Repository
class EmailOutbox {

	/** Pause before each retry; after the last, the email is given up as FAILED. */
	static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4), Duration.ofHours(12));

	private final JdbcClient jdbcClient;

	EmailOutbox(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	void enqueue(String recipient, String subject, String body, UUID reservationId, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO email_outbox (id, recipient, subject, body, reservation_id, status, next_attempt_at,
					created_at)
				VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)""")
			.params(UUID.randomUUID(), recipient, subject, body, reservationId, Timestamp.from(now),
					Timestamp.from(now))
			.update();
	}

	/**
	 * Locks the next email that is due. Locked rows are skipped, so several servers can send
	 * side by side without sending anything twice.
	 */
	Optional<Email> claimNext(Instant now) {
		return this.jdbcClient.sql("""
				SELECT id, recipient, subject, body, reservation_id, attempts FROM email_outbox
				WHERE status = 'PENDING' AND next_attempt_at <= ?
				ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED""")
			.param(Timestamp.from(now))
			.query((rs, row) -> new Email(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
					rs.getString(4), rs.getObject(5, UUID.class), rs.getInt(6)))
			.optional();
	}

	void markSent(UUID id, Instant now) {
		this.jdbcClient.sql("UPDATE email_outbox SET status = 'SENT', attempts = attempts + 1, sent_at = ? WHERE id = ?")
			.params(Timestamp.from(now), id)
			.update();
	}

	/** Records a failed send: retried later, or FAILED once the retries are used up. */
	void markFailed(Email email, String error, Instant now) {
		int attempts = email.attempts() + 1;
		boolean giveUp = attempts > RETRY_DELAYS.size();
		Instant next = giveUp ? now : now.plus(RETRY_DELAYS.get(attempts - 1));
		this.jdbcClient.sql("""
				UPDATE email_outbox SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?""")
			.params(giveUp ? "FAILED" : "PENDING", attempts, Timestamp.from(next), error, email.id())
			.update();
	}

	record Email(UUID id, String recipient, String subject, String body, UUID reservationId, int attempts) {
	}

}
