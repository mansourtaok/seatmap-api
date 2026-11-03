package com.seatmap.api.event;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.seatmap.api.event.EventViews.ReservationStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reservations and what is taken. Plain SQL, because the guarantees live in the statements:
 * a seat is claimed by inserting its {@code event_seat} row (the primary key lets only one
 * reservation have it), and an area's places are counted under a per-area lock.
 */
@Repository
class ReservationRepository {

	private final JdbcClient jdbcClient;

	ReservationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** The version's units with the given ids, by id. */
	Map<String, SellableUnit> findUnits(UUID versionId, List<String> unitIds) {
		Map<String, SellableUnit> units = new HashMap<>();
		this.jdbcClient.sql("""
				SELECT unit_id, kind, capacity, label, ticket_category_id, design_status
				FROM inventory_unit WHERE version_id = ? AND unit_id = ANY (?)""")
			.params(versionId, unitIds.toArray(String[]::new))
			.query((rs) -> {
				SellableUnit unit = new SellableUnit(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
						rs.getString(5), rs.getString(6));
				units.put(unit.unitId(), unit);
			});
		return units;
	}

	List<SellableUnit> findAllUnits(UUID versionId) {
		return this.jdbcClient.sql("""
				SELECT unit_id, kind, capacity, label, ticket_category_id, design_status
				FROM inventory_unit WHERE version_id = ? ORDER BY sort_order""")
			.param(versionId)
			.query((rs, row) -> new SellableUnit(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
					rs.getString(5), rs.getString(6)))
			.list();
	}

	/** Seats the designer marked held or blocked, by id. */
	Map<String, String> findDesignStatuses(UUID versionId) {
		Map<String, String> statuses = new HashMap<>();
		this.jdbcClient.sql("""
				SELECT unit_id, design_status FROM inventory_unit
				WHERE version_id = ? AND kind = 'SEAT' AND design_status IS NOT NULL""")
			.param(versionId)
			.query((rs) -> {
				statuses.put(rs.getString(1), rs.getString(2));
			});
		return statuses;
	}

	void insert(UUID id, UUID eventId, Instant expiresAt, BigDecimal total, String clientIp, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO reservation (id, event_id, status, expires_at, total, client_ip, created_at)
				VALUES (?, ?, 'HELD', ?, ?, ?, ?)""")
			.params(id, eventId, Timestamp.from(expiresAt), total, clientIp, Timestamp.from(now))
			.update();
	}

	/** Places a client currently holds at an event. */
	int countHeldByClient(UUID eventId, String clientIp, Instant now) {
		return this.jdbcClient.sql("""
				SELECT coalesce(sum(i.quantity), 0) FROM reservation r
				JOIN reservation_item i ON i.reservation_id = r.id
				WHERE r.event_id = ? AND r.client_ip = ? AND r.status = 'HELD' AND r.expires_at > ?""")
			.params(eventId, clientIp, Timestamp.from(now))
			.query(Integer.class)
			.single();
	}

	void insertItem(UUID reservationId, UUID eventId, String unitId, int quantity, String categoryId,
			BigDecimal unitPrice) {
		this.jdbcClient.sql("""
				INSERT INTO reservation_item (reservation_id, event_id, unit_id, quantity, category_id, unit_price)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(reservationId, eventId, unitId, quantity, categoryId, unitPrice)
			.update();
	}

	/**
	 * Claims a seat for a reservation: inserts its row, or takes over a hold that has expired.
	 * Returns false if someone else has it. A concurrent claim waits for this transaction, then
	 * finds the row taken.
	 */
	boolean claimSeat(UUID eventId, String unitId, UUID reservationId, Instant expiresAt, Instant now) {
		return this.jdbcClient.sql("""
				INSERT INTO event_seat (event_id, unit_id, reservation_id, status, expires_at)
				VALUES (?, ?, ?, 'HELD', ?)
				ON CONFLICT (event_id, unit_id) DO UPDATE
				SET reservation_id = EXCLUDED.reservation_id, status = 'HELD', expires_at = EXCLUDED.expires_at
				WHERE event_seat.status = 'HELD' AND event_seat.expires_at <= ?""")
			.params(eventId, unitId, reservationId, Timestamp.from(expiresAt), Timestamp.from(now))
			.update() == 1;
	}

	/** Serializes everyone counting one area's places, until the transaction ends. */
	void lockArea(UUID eventId, String unitId) {
		this.jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
			.param(eventId + "/" + unitId)
			.query()
			.listOfRows();
	}

	/** Places of an area that are sold or held, leaving out one reservation's own. */
	int countTaken(UUID eventId, String unitId, UUID excludedReservation, Instant now) {
		return this.jdbcClient.sql("""
				SELECT coalesce(sum(i.quantity), 0) FROM reservation_item i
				JOIN reservation r ON r.id = i.reservation_id
				WHERE i.event_id = ? AND i.unit_id = ? AND r.id <> ?
				AND (r.status = 'CONFIRMED' OR (r.status = 'HELD' AND r.expires_at > ?))""")
			.params(eventId, unitId, excludedReservation, Timestamp.from(now))
			.query(Integer.class)
			.single();
	}

	/** Seats that are sold, or held and not expired: SOLD or HELD by unit id. */
	Map<String, String> findTakenSeats(UUID eventId, Instant now) {
		Map<String, String> taken = new HashMap<>();
		this.jdbcClient.sql("""
				SELECT unit_id, status FROM event_seat
				WHERE event_id = ? AND (status = 'SOLD' OR expires_at > ?)""")
			.params(eventId, Timestamp.from(now))
			.query((rs) -> {
				taken.put(rs.getString(1), rs.getString(2));
			});
		return taken;
	}

	/** Sold and held places per unit, from reservations: {sold, held} by unit id. */
	Map<String, int[]> countPlaces(UUID eventId, Instant now) {
		Map<String, int[]> counts = new HashMap<>();
		this.jdbcClient.sql("""
				SELECT i.unit_id,
					coalesce(sum(i.quantity) FILTER (WHERE r.status = 'CONFIRMED'), 0),
					coalesce(sum(i.quantity) FILTER (WHERE r.status = 'HELD' AND r.expires_at > ?), 0)
				FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id
				WHERE i.event_id = ? GROUP BY i.unit_id""")
			.params(Timestamp.from(now), eventId)
			.query((rs) -> {
				counts.put(rs.getString(1), new int[] { rs.getInt(2), rs.getInt(3) });
			});
		return counts;
	}

	Optional<Row> find(UUID id) {
		return this.jdbcClient.sql("SELECT id, event_id, status, expires_at, total FROM reservation WHERE id = ?")
			.param(id)
			.query(ReservationRepository::row)
			.optional();
	}

	/** Locks the reservation, so confirming, releasing and expiring it happen one at a time. */
	Optional<Row> findForUpdate(UUID id) {
		return this.jdbcClient
			.sql("SELECT id, event_id, status, expires_at, total FROM reservation WHERE id = ? FOR UPDATE")
			.param(id)
			.query(ReservationRepository::row)
			.optional();
	}

	List<Item> findItems(UUID reservationId) {
		return this.jdbcClient.sql("""
				SELECT i.unit_id, u.label, u.kind, i.quantity, i.category_id, i.unit_price
				FROM reservation_item i
				JOIN event e ON e.id = i.event_id
				JOIN inventory_unit u ON u.version_id = e.version_id AND u.unit_id = i.unit_id
				WHERE i.reservation_id = ? ORDER BY u.sort_order""")
			.param(reservationId)
			.query((rs, row) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
					rs.getString(5), rs.getBigDecimal(6)))
			.list();
	}

	/** Turns the reservation's held seats into sold ones; returns how many it changed. */
	int sellSeats(UUID reservationId) {
		return this.jdbcClient.sql("""
				UPDATE event_seat SET status = 'SOLD', expires_at = NULL
				WHERE reservation_id = ? AND status = 'HELD'""")
			.param(reservationId)
			.update();
	}

	void freeSeats(UUID reservationId) {
		this.jdbcClient.sql("DELETE FROM event_seat WHERE reservation_id = ? AND status = 'HELD'")
			.param(reservationId)
			.update();
	}

	void markConfirmed(UUID reservationId, Instant now) {
		this.jdbcClient.sql("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?")
			.params(Timestamp.from(now), reservationId)
			.update();
	}

	void markReleased(UUID reservationId, Instant now) {
		this.jdbcClient.sql("UPDATE reservation SET status = 'RELEASED', released_at = ? WHERE id = ?")
			.params(Timestamp.from(now), reservationId)
			.update();
	}

	/**
	 * Releases every hold that has expired and frees its seats. A seat already taken over by a
	 * newer hold belongs to that reservation now, so it is left alone.
	 *
	 * @return the number of seats freed
	 */
	int releaseExpired(Instant now) {
		return this.jdbcClient.sql("""
				WITH expired AS (
					UPDATE reservation SET status = 'RELEASED', released_at = ?
					WHERE status = 'HELD' AND expires_at <= ? RETURNING id)
				DELETE FROM event_seat s USING expired
				WHERE s.reservation_id = expired.id AND s.status = 'HELD'""")
			.params(Timestamp.from(now), Timestamp.from(now))
			.update();
	}

	boolean existsForEvent(UUID eventId) {
		return this.jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM reservation WHERE event_id = ?)")
			.param(eventId)
			.query(Boolean.class)
			.single();
	}

	private static Row row(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
		return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
				ReservationStatus.valueOf(rs.getString(3)), rs.getTimestamp(4).toInstant(), rs.getBigDecimal(5));
	}

	/** An inventory unit as far as selling it is concerned. */
	record SellableUnit(String unitId, String kind, int capacity, String label, String categoryId,
			String designStatus) {

		boolean isSeat() {
			return "SEAT".equals(this.kind);
		}

	}

	record Row(UUID id, UUID eventId, ReservationStatus status, Instant expiresAt, BigDecimal total) {
	}

	record Item(String unitId, String label, String kind, int quantity, String categoryId, BigDecimal unitPrice) {
	}

}
