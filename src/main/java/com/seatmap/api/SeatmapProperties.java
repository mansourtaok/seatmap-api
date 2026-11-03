package com.seatmap.api;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under {@code seatmap.*}.
 *
 * @param maxDocumentSize largest layout document accepted (a 100,000-seat stadium is about 30 MB)
 * @param maxAssetSize largest floor-plan image accepted
 * @param corsAllowedOrigins browser origins allowed to call the API, such as the Angular dev server
 * @param holdDuration how long a reservation keeps its seats before they go back on sale
 * @param maxReservationItems most seats and areas one reservation may hold
 * @param holdsPerMinute most holds one guest (by IP address) may make per minute
 * @param maxHeldPerGuest most places one guest may hold at once per event; staff aren't limited
 * @param maxFailedLogins failed logins in a row that lock an account
 * @param loginLockDuration how long a locked account stays locked
 */
@ConfigurationProperties("seatmap")
public record SeatmapProperties(@DefaultValue("50MB") DataSize maxDocumentSize,
		@DefaultValue("20MB") DataSize maxAssetSize, @DefaultValue("http://localhost:4200") List<String> corsAllowedOrigins,
		@DefaultValue("10m") Duration holdDuration, @DefaultValue("50") int maxReservationItems,
		@DefaultValue("10") int holdsPerMinute, @DefaultValue("20") int maxHeldPerGuest,
		@DefaultValue("10") int maxFailedLogins, @DefaultValue("15m") Duration loginLockDuration) {
}
