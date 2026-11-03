package com.seatmap.api.web;

/** Not logged in, or the login failed: 401. */
public class UnauthorizedException extends RuntimeException {

	public UnauthorizedException(String message) {
		super(message);
	}

}
