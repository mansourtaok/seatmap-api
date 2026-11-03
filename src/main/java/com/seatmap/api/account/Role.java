package com.seatmap.api.account;

/** A member's role in an organization; each includes what the ones before it can do. */
public enum Role {

	/** Sees layouts, events, availability and reservations. */
	VIEWER,

	/** Also edits and publishes layouts, runs events and sets prices, and confirms sales at the box office. */
	EDITOR,

	/** Also manages members and deletes layouts. */
	ADMIN;

	public boolean includes(Role other) {
		return compareTo(other) >= 0;
	}

}
