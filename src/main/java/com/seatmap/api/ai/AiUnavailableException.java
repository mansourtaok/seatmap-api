package com.seatmap.api.ai;

/** The AI service failed, timed out or isn't configured: the user should try again later (503). */
public class AiUnavailableException extends RuntimeException {

	public AiUnavailableException(String message) {
		super(message);
	}

	public AiUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
