package com.seatmap.api.layout;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A version's inventory units and ticket categories. Plain JDBC rather than JPA: they are
 * written once, in bulk (a stadium has tens of thousands of seats), and only ever read back.
 */
@Repository
class InventoryRepository {

	private static final int BATCH_SIZE = 1000;

	private final JdbcTemplate jdbcTemplate;

	private final JdbcClient jdbcClient;

	InventoryRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
		this.jdbcTemplate = jdbcTemplate;
		this.jdbcClient = jdbcClient;
	}

	void insert(UUID versionId, Inventory inventory) {
		List<InventoryUnit> units = inventory.units();
		int[] order = { 0 };
		this.jdbcTemplate.batchUpdate("""
				INSERT INTO inventory_unit (version_id, unit_id, sort_order, kind, capacity, parent_id, parent_name,
					block, row_label, seat_number, label, ticket_category_id, design_status, x, y)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", units, BATCH_SIZE, (statement, unit) -> {
			statement.setObject(1, versionId);
			statement.setString(2, unit.unitId());
			statement.setInt(3, order[0]++);
			statement.setString(4, unit.kind().name());
			statement.setInt(5, unit.capacity());
			statement.setString(6, unit.parentId());
			statement.setString(7, unit.parentName());
			statement.setString(8, unit.block());
			statement.setString(9, unit.rowLabel());
			statement.setString(10, unit.seatNumber());
			statement.setString(11, unit.label());
			statement.setString(12, unit.ticketCategoryId());
			statement.setString(13, unit.designStatus());
			statement.setDouble(14, unit.x());
			statement.setDouble(15, unit.y());
		});
		this.jdbcTemplate.batchUpdate("""
				INSERT INTO ticket_category (version_id, category_id, name, color, default_price)
				VALUES (?, ?, ?, ?, ?)""", inventory.categories(), BATCH_SIZE, (statement, category) -> {
			statement.setObject(1, versionId);
			statement.setString(2, category.id());
			statement.setString(3, category.name());
			statement.setString(4, category.color());
			statement.setObject(5, category.defaultPrice(), Types.NUMERIC);
		});
	}

	Inventory find(UUID versionId) {
		List<TicketCategory> categories = this.jdbcClient.sql("""
				SELECT category_id, name, color, default_price FROM ticket_category
				WHERE version_id = ? ORDER BY name""")
			.param(versionId)
			.query((rs, row) -> new TicketCategory(rs.getString(1), rs.getString(2), rs.getString(3),
					rs.getBigDecimal(4)))
			.list();
		List<InventoryUnit> units = this.jdbcClient.sql("""
				SELECT unit_id, kind, capacity, parent_id, parent_name, block, row_label, seat_number, label,
					ticket_category_id, design_status, x, y
				FROM inventory_unit WHERE version_id = ? ORDER BY sort_order""")
			.param(versionId)
			.query((rs, row) -> new InventoryUnit(rs.getString(1), InventoryUnit.Kind.valueOf(rs.getString(2)),
					rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
					rs.getString(9), rs.getString(10), rs.getString(11), rs.getDouble(12), rs.getDouble(13)))
			.list();
		return new Inventory(categories, units);
	}

}
