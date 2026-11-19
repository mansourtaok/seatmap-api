package com.seatmap.api.ai;

import java.time.Instant;

/** The organization has used this month's AI allowance (402 Payment Required). */
public class AiAllowanceUsedException extends RuntimeException {

	private final Instant resetsAt;

	public AiAllowanceUsedException(String message, Instant resetsAt) {
		super(message);
		this.resetsAt = resetsAt;
	}

	public Instant getResetsAt() {
		return this.resetsAt;
	}

}
