package com.seatmap.api.event;

import java.util.Map;
import java.util.Set;

/**
 * Where an event is in its life. Tickets can only be held while it is {@link #ON_SALE};
 * sales can be paused by going back to {@link #DRAFT} or reopened from {@link #CLOSED}.
 */
public enum EventStatus {

	DRAFT, ON_SALE, CLOSED, CANCELLED;

	private static final Map<EventStatus, Set<EventStatus>> NEXT = Map.of(DRAFT, Set.of(ON_SALE, CANCELLED), ON_SALE,
			Set.of(DRAFT, CLOSED, CANCELLED), CLOSED, Set.of(ON_SALE, CANCELLED), CANCELLED, Set.of());

	boolean canBecome(EventStatus next) {
		return next == this || NEXT.get(this).contains(next);
	}

}
