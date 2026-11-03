package com.seatmap.api.web;

/** Logged in, but the role doesn't allow it: 403. */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
