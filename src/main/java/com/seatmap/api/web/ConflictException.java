package com.seatmap.api.web;

import java.util.List;

/**
 * The request clashes with the resource's current state, such as a stale revision or a seat
 * someone else holds: 409. {@code problems} can list the specifics.
 */
public class ConflictException extends RuntimeException {

	private final List<String> problems;

	public ConflictException(String message, List<String> problems) {
		super(message);
		this.problems = List.copyOf(problems);
	}

	public ConflictException(String message) {
		this(message, List.of());
	}

	public List<String> getProblems() {
		return this.problems;
	}

}
