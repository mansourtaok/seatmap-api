package com.seatmap.api.account;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the account and organization APIs. */
public final class AccountViews {

	private AccountViews() {
	}

	/** Signs up and creates the organization they'll be the admin of. */
	record Register(@NotBlank @Email @Size(max = 254) String email, @NotNull @Size(min = 10, max = 128) String password,
			@NotBlank @Size(max = 100) String displayName, @NotBlank @Size(max = 200) String organizationName) {
	}

	record Login(@NotBlank String email, @NotNull String password) {
	}

	record CreateOrganization(@NotBlank @Size(max = 200) String name) {
	}

	record AddMember(@NotBlank @Email String email, @NotNull Role role) {
	}

	record ChangeRole(@NotNull Role role) {
	}

	/** The logged-in user and their organizations. */
	public record Me(UUID id, String email, String displayName, List<Membership> organizations) {
	}

	public record Membership(UUID organizationId, String name, Role role) {
	}

	public record Member(UUID userId, String email, String displayName, Role role) {
	}

}
