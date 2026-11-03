package com.seatmap.api.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the events API. */
final class EventRequests {

	private EventRequests() {
	}

	/** Creates a draft event selling version {@code version} of layout {@code layoutId}. */
	record CreateEvent(@NotNull UUID layoutId, @Min(1) int version, @NotBlank @Size(max = 200) String name,
			@NotNull Instant startsAt, Instant endsAt, @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
			@NotBlank String timeZone) {
	}

	/** Replaces the event's details; the layout version is fixed once created. */
	record UpdateEvent(@NotBlank @Size(max = 200) String name, @NotNull Instant startsAt, Instant endsAt,
			@NotNull @Pattern(regexp = "[A-Z]{3}") String currency, @NotBlank String timeZone,
			@NotNull EventStatus status) {
	}

	/** New prices by ticket category id; categories not listed keep theirs. */
	record UpdatePrices(@NotEmpty Map<String, @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal> prices) {
	}

	/** Who the tickets are for. The email is where they're sent. */
	record Buyer(@NotBlank @Size(max = 100) String name, @Email @Size(max = 254) String email,
			@Size(max = 40) String phone) {
	}

	/** Seats (quantity 1) and standing places to hold. */
	record Hold(@NotEmpty List<@Valid Item> items) {

		record Item(@NotBlank String unitId, @Min(1) int quantity) {
		}

	}

}
