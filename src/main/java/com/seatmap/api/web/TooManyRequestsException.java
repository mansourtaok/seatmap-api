package com.seatmap.api.web;

import java.time.Duration;

/** The client is over a limit; it may try again after {@code retryAfter}: 429. */
public class TooManyRequestsException extends RuntimeException {

	private final Duration retryAfter;

	public TooManyRequestsException(String message, Duration retryAfter) {
		super(message);
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return this.retryAfter;
	}

}
