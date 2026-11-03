package com.seatmap.api.web;

import java.util.List;

/** The request body is unusable; {@code problems} lists why: 400. */
public class InvalidRequestException extends RuntimeException {

	private final List<String> problems;

	public InvalidRequestException(String message, List<String> problems) {
		super(message);
		this.problems = List.copyOf(problems);
	}

	public InvalidRequestException(String message) {
		this(message, List.of());
	}

	public List<String> getProblems() {
		return this.problems;
	}

}
