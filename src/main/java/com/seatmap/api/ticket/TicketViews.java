package com.seatmap.api.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request and response bodies of the tickets API. */
final class TicketViews {

	private TicketViews() {
	}

	record CheckInRequest(@NotBlank String code) {
	}

	/**
	 * What the door scanner shows. {@code ticketId}, {@code label} and {@code categoryName} are
	 * there when the ticket is genuine; {@code checkedInAt} says when it was (first) let in.
	 */
	record CheckInResult(Result result, UUID ticketId, String label, String categoryName, Instant checkedInAt) {

		enum Result {

			/** Let them in. */
			VALID,

			/** Genuine, but already used. */
			ALREADY_CHECKED_IN,

			/** Forged, damaged, or for another event. */
			NOT_VALID

		}

	}

	record CheckInStats(int issued, int checkedIn, List<CategoryCheckIns> categories) {
	}

	record CategoryCheckIns(String categoryId, String name, int issued, int checkedIn) {
	}

	record ResendRequest(@NotBlank @Email String email) {
	}

}
