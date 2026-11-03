package com.seatmap.api.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

/** Response bodies of the events API. */
final class EventViews {

	private EventViews() {
	}

	record EventView(UUID id, UUID organizationId, UUID layoutId, int version, String name, Instant startsAt,
			Instant endsAt, String currency, EventStatus status, int revision, List<Price> prices) {
	}

	/** An on-sale event as buyers see it, with the layout document to draw the seat map from. */
	record PublicEvent(EventView event, @JsonRawValue String layout) {
	}

	record Price(String categoryId, String name, String color, BigDecimal price) {
	}

	/**
	 * What's left to sell. Seats the designer blocked or held, and anything without a ticket
	 * category, count as {@code unavailable}.
	 */
	record Availability(List<CategoryAvailability> categories, List<AreaAvailability> areas, int withoutCategory) {
	}

	record CategoryAvailability(String categoryId, String name, int capacity, int sold, int held, int unavailable,
			int available) {
	}

	record AreaAvailability(String unitId, String label, String categoryId, int capacity, int sold, int held,
			int available) {
	}

	record ReservationView(UUID id, UUID eventId, ReservationStatus status, Instant expiresAt, BigDecimal total,
			String currency, List<ReservationItem> items) {
	}

	record ReservationItem(String unitId, String label, int quantity, String categoryId, BigDecimal unitPrice) {
	}

	enum ReservationStatus {

		HELD, CONFIRMED, RELEASED

	}

}
