package com.seatmap.api.web;

/** The request clashes with the resource's current state, such as a stale revision: 409. */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}

}
