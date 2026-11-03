package com.seatmap.api.layout;

import java.time.Instant;

public record VersionSummary(int version, int seatCount, int totalCapacity, Instant publishedAt) {
}
