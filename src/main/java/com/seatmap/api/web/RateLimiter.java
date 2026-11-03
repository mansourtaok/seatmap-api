package com.seatmap.api.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code limit} actions per key (such as an IP address) in each {@code window}.
 * Counts live in memory: with several servers each counts on its own, which loosens the
 * limit but still stops one client hammering one server.
 */
public class RateLimiter {

	private final int limit;

	private final Duration window;

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	public RateLimiter(int limit, Duration window) {
		this.limit = limit;
		this.window = window;
	}

	/** Counts an action, or refuses it with 429 if the key is over the limit. */
	public void acquire(String key, String refusal) {
		Instant now = Instant.now();
		if (this.windows.size() > 10_000) {
			this.windows.values().removeIf((window) -> window.endsBefore(now, this.window));
		}
		Window window = this.windows.compute(key, (ignored, current) -> (current == null
				|| current.endsBefore(now, this.window)) ? new Window(now, 1) : current.next());
		if (window.count() > this.limit) {
			throw new TooManyRequestsException(refusal, Duration.between(now, window.start().plus(this.window)));
		}
	}

	private record Window(Instant start, int count) {

		boolean endsBefore(Instant now, Duration length) {
			return !this.start.plus(length).isAfter(now);
		}

		Window next() {
			return new Window(this.start, this.count + 1);
		}

	}

}
