package com.seatmap.api.event;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** An event's prices, one per ticket category of its layout version. */
@Repository
class PriceRepository {

	private final JdbcClient jdbcClient;

	PriceRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** Starts the event at the version's default prices. */
	void copyDefaults(UUID eventId, UUID versionId) {
		this.jdbcClient.sql("""
				INSERT INTO event_price (event_id, category_id, price)
				SELECT ?, category_id, default_price FROM ticket_category WHERE version_id = ?""")
			.params(eventId, versionId)
			.update();
	}

	List<EventViews.Price> find(UUID eventId) {
		return this.jdbcClient.sql("""
				SELECT p.category_id, c.name, c.color, p.price
				FROM event_price p
				JOIN event e ON e.id = p.event_id
				JOIN ticket_category c ON c.version_id = e.version_id AND c.category_id = p.category_id
				WHERE p.event_id = ? ORDER BY c.name""")
			.param(eventId)
			.query((rs, row) -> new EventViews.Price(rs.getString(1), rs.getString(2), rs.getString(3),
					rs.getBigDecimal(4)))
			.list();
	}

	Map<String, BigDecimal> findByCategory(UUID eventId) {
		Map<String, BigDecimal> prices = new HashMap<>();
		this.jdbcClient.sql("SELECT category_id, price FROM event_price WHERE event_id = ?")
			.param(eventId)
			.query((rs) -> {
				prices.put(rs.getString(1), rs.getBigDecimal(2));
			});
		return prices;
	}

	Set<String> categoryIds(UUID versionId) {
		return this.jdbcClient.sql("SELECT category_id FROM ticket_category WHERE version_id = ?")
			.param(versionId)
			.query(String.class)
			.set();
	}

	void update(UUID eventId, Map<String, BigDecimal> prices) {
		prices.forEach((categoryId, price) -> this.jdbcClient.sql("""
				INSERT INTO event_price (event_id, category_id, price) VALUES (?, ?, ?)
				ON CONFLICT (event_id, category_id) DO UPDATE SET price = EXCLUDED.price""")
			.params(eventId, categoryId, price)
			.update());
	}

}
