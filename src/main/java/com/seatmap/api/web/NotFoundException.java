package com.seatmap.api.web;

/** The requested resource doesn't exist: 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
