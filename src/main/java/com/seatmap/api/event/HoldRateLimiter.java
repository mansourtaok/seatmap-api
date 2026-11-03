package com.seatmap.api.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.seatmap.api.SeatmapProperties;
import com.seatmap.api.web.TooManyRequestsException;

import org.springframework.stereotype.Component;

/**
 * Caps how many holds a guest (by IP address) can make per minute, so a script can't keep
 * holding every seat of an event. Counts live in memory: with several servers each counts
 * on its own, which loosens the cap but still stops a single client hammering one server.
 */
@Component
class HoldRateLimiter {

	private static final Duration WINDOW = Duration.ofMinutes(1);

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final int holdsPerWindow;

	HoldRateLimiter(SeatmapProperties properties) {
		this.holdsPerWindow = properties.holdsPerMinute();
	}

	/** Counts a hold attempt, or refuses it if the client is over the limit. */
	void acquire(String clientIp) {
		Instant now = Instant.now();
		if (this.windows.size() > 10_000) {
			this.windows.values().removeIf((window) -> window.endsBefore(now));
		}
		Window window = this.windows.compute(clientIp,
				(ip, current) -> (current == null || current.endsBefore(now)) ? new Window(now, 1) : current.next());
		if (window.count() > this.holdsPerWindow) {
			throw new TooManyRequestsException("Too many holds in a short time; wait a moment and try again",
					Duration.between(now, window.start().plus(WINDOW)));
		}
	}

	private record Window(Instant start, int count) {

		boolean endsBefore(Instant now) {
			return !this.start.plus(WINDOW).isAfter(now);
		}

		Window next() {
			return new Window(this.start, this.count + 1);
		}

	}

}
