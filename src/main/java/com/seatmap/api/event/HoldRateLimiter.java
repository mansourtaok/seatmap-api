package com.seatmap.api.event;

import java.time.Duration;

import com.seatmap.api.SeatmapProperties;
import com.seatmap.api.web.RateLimiter;

import org.springframework.stereotype.Component;

/** Caps how many holds a guest (by IP address) makes per minute, so a script can't hold a whole event. */
@Component
class HoldRateLimiter {

	private final RateLimiter limiter;

	HoldRateLimiter(SeatmapProperties properties) {
		this.limiter = new RateLimiter(properties.holdsPerMinute(), Duration.ofMinutes(1));
	}

	void acquire(String clientIp) {
		this.limiter.acquire(clientIp, "Too many holds in a short time; wait a moment and try again");
	}

}
