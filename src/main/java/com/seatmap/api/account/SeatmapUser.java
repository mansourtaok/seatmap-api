package com.seatmap.api.account;

import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Who is logged in, as kept in their session. Roles aren't kept here: they are read from the
 * database on each request, so a changed or removed membership applies at once.
 */
public record SeatmapUser(UUID id, String email, String displayName) implements AuthenticatedPrincipal, Serializable {

	/** The id, which Spring Session indexes sessions by (to end all of a user's sessions, say). */
	@Override
	public String getName() {
		return this.id.toString();
	}

}
