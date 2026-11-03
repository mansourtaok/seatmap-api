package com.seatmap.api.ticket;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tickets, and what printing and checking them needs to know about their event and order. */
@Repository
class TicketRepository {

	private final JdbcClient jdbcClient;

	TicketRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * Issues a confirmed reservation's tickets: one per seat and one per standing place, at the
	 * price it was held at. Issuing again adds nothing.
	 *
	 * @return how many tickets the reservation has
	 */
	int issue(UUID reservationId, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO ticket (id, event_id, reservation_id, unit_id, place, label, category_id, category_name,
					price, status, issued_at)
				SELECT gen_random_uuid(), i.event_id, i.reservation_id, i.unit_id, place.n, u.label, i.category_id,
					c.name, i.unit_price, 'VALID', ?
				FROM reservation_item i
				JOIN event e ON e.id = i.event_id
				JOIN inventory_unit u ON u.version_id = e.version_id AND u.unit_id = i.unit_id
				JOIN ticket_category c ON c.version_id = e.version_id AND c.category_id = i.category_id
				CROSS JOIN LATERAL generate_series(1, i.quantity) AS place(n)
				WHERE i.reservation_id = ?
				ON CONFLICT (reservation_id, unit_id, place) DO NOTHING""")
			.params(Timestamp.from(now), reservationId)
			.update();
		return this.jdbcClient.sql("SELECT count(*) FROM ticket WHERE reservation_id = ?")
			.param(reservationId)
			.query(Integer.class)
			.single();
	}

	/** A reservation's tickets, in seat-map order. */
	List<Ticket> findByReservation(UUID reservationId) {
		return this.jdbcClient.sql("""
				SELECT t.id, t.label, t.place, i.quantity, t.category_name, t.price, t.status
				FROM ticket t
				JOIN reservation_item i ON i.reservation_id = t.reservation_id AND i.unit_id = t.unit_id
				JOIN event e ON e.id = t.event_id
				JOIN inventory_unit u ON u.version_id = e.version_id AND u.unit_id = t.unit_id
				WHERE t.reservation_id = ? ORDER BY u.sort_order, t.place""")
			.param(reservationId)
			.query((rs, row) -> new Ticket(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
					rs.getString(5), rs.getBigDecimal(6), rs.getString(7)))
			.list();
	}

	/** What a ticket PDF or email says about the order and its event. */
	Optional<Order> findOrder(UUID reservationId) {
		return this.jdbcClient.sql("""
				SELECT r.id, r.status, r.buyer_name, r.buyer_email, e.id, e.name, e.starts_at, e.time_zone,
					e.currency, o.name, v.document ->> 'name', e.organization_id
				FROM reservation r
				JOIN event e ON e.id = r.event_id
				JOIN organization o ON o.id = e.organization_id
				JOIN layout_version v ON v.id = e.version_id
				WHERE r.id = ?""")
			.param(reservationId)
			.query((rs, row) -> new Order(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
					rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(),
					ZoneId.of(rs.getString(8)), rs.getString(9), rs.getString(10), rs.getString(11),
					rs.getObject(12, UUID.class)))
			.optional();
	}

	/** Confirmed orders sent to an email address, latest first. */
	List<UUID> findConfirmedByEmail(String email, int limit) {
		return this.jdbcClient.sql("""
				SELECT id FROM reservation WHERE lower(buyer_email) = lower(?) AND status = 'CONFIRMED'
				ORDER BY confirmed_at DESC LIMIT ?""")
			.params(email.strip(), limit)
			.query(UUID.class)
			.list();
	}

	Optional<UUID> findEventOrganization(UUID eventId) {
		return this.jdbcClient.sql("SELECT organization_id FROM event WHERE id = ?")
			.param(eventId)
			.query(UUID.class)
			.optional();
	}

	/**
	 * Checks a ticket in if it is valid and for this event. One statement, so of two gates
	 * scanning the same ticket at once only one succeeds.
	 */
	Optional<CheckIn> checkIn(UUID ticketId, UUID eventId, UUID userId, Instant now) {
		return this.jdbcClient.sql("""
				UPDATE ticket SET status = 'CHECKED_IN', checked_in_at = ?, checked_in_by = ?
				WHERE id = ? AND event_id = ? AND status = 'VALID'
				RETURNING id, label, category_name, checked_in_at""")
			.params(Timestamp.from(now), userId, ticketId, eventId)
			.query(TicketRepository::checkIn)
			.optional();
	}

	/** The ticket's check-in, if it was already checked in at this event. */
	Optional<CheckIn> findCheckIn(UUID ticketId, UUID eventId) {
		return this.jdbcClient.sql("""
				SELECT id, label, category_name, checked_in_at FROM ticket
				WHERE id = ? AND event_id = ? AND status = 'CHECKED_IN'""")
			.params(ticketId, eventId)
			.query(TicketRepository::checkIn)
			.optional();
	}

	boolean undoCheckIn(UUID ticketId, UUID eventId) {
		return this.jdbcClient.sql("""
				UPDATE ticket SET status = 'VALID', checked_in_at = NULL, checked_in_by = NULL
				WHERE id = ? AND event_id = ? AND status = 'CHECKED_IN'""")
			.params(ticketId, eventId)
			.update() == 1;
	}

	List<TicketViews.CategoryCheckIns> countCheckIns(UUID eventId) {
		return this.jdbcClient.sql("""
				SELECT category_id, category_name, count(*), count(*) FILTER (WHERE status = 'CHECKED_IN')
				FROM ticket WHERE event_id = ? GROUP BY category_id, category_name ORDER BY category_name""")
			.param(eventId)
			.query((rs, row) -> new TicketViews.CategoryCheckIns(rs.getString(1), rs.getString(2), rs.getInt(3),
					rs.getInt(4)))
			.list();
	}

	private static CheckIn checkIn(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new CheckIn(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
				rs.getTimestamp(4).toInstant());
	}

	/** {@code place} is 1 of {@code places} for standing areas, 1 of 1 for seats. */
	record Ticket(UUID id, String label, int place, int places, String categoryName, BigDecimal price,
			String status) {
	}

	record Order(UUID reservationId, String status, String buyerName, String buyerEmail, UUID eventId,
			String eventName, Instant startsAt, ZoneId timeZone, String currency, String organizationName,
			String venueName, UUID organizationId) {
	}

	record CheckIn(UUID ticketId, String label, String categoryName, Instant checkedInAt) {
	}

}
