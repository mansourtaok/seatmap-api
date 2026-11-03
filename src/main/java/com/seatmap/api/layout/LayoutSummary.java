package com.seatmap.api.layout;

import java.time.Instant;
import java.util.UUID;

/** A layout in a list: no document. {@code latestVersion} is null until the first publish. */
public record LayoutSummary(UUID id, UUID organizationId, String name, String description, int revision, Integer latestVersion,
		Instant createdAt, Instant updatedAt) {
}
