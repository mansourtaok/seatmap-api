package com.seatmap.api.web;

/** The request is bigger than the API accepts for it: 413. */
public class PayloadTooLargeException extends RuntimeException {

	public PayloadTooLargeException(String message) {
		super(message);
	}

}
