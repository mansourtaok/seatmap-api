package com.seatmap.api.dev;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development only: wipes every layout, version, event, sale and ticket so a test can start
 * over. Accounts, organizations and sessions stay. The bean only exists when
 * {@code seatmap.dev.data-reset-enabled=true}, so production has no such endpoint (404).
 */
@RestController
@ConditionalOnBooleanProperty("seatmap.dev.data-reset-enabled")
class DevDataController {

	/** All in one statement: Postgres needs every table that references another in the same TRUNCATE. */
	static final List<String> TABLES = List.of("email_outbox", "ticket", "event_seat", "reservation_item",
			"reservation", "event_price", "event", "asset", "ticket_category", "inventory_unit", "layout_version",
			"layout");

	private final JdbcClient jdbcClient;

	DevDataController(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@DeleteMapping("/api/dev/data")
	@Transactional
	ResponseEntity<Void> wipe() {
		this.jdbcClient.sql("TRUNCATE " + String.join(", ", TABLES)).update();
		return ResponseEntity.noContent().build();
	}

}
