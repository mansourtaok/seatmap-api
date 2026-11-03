package com.seatmap.api.account;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The current user's organizations and their members. */
@RestController
class OrganizationController {

	private final AccountService accounts;

	private final Access access;

	OrganizationController(AccountService accounts, Access access) {
		this.accounts = accounts;
		this.access = access;
	}

	@GetMapping("/api/organizations")
	List<AccountViews.Membership> mine() {
		return this.access.memberships();
	}

	@PostMapping("/api/organizations")
	ResponseEntity<AccountViews.Membership> create(@Valid @RequestBody AccountViews.CreateOrganization body) {
		AccountViews.Membership organization = this.accounts.createOrganization(body.name());
		return ResponseEntity.created(URI.create("/api/organizations/" + organization.organizationId()))
			.body(organization);
	}

	@GetMapping("/api/organizations/{id}/members")
	List<AccountViews.Member> members(@PathVariable UUID id) {
		return this.accounts.members(id);
	}

	@PostMapping("/api/organizations/{id}/members")
	ResponseEntity<AccountViews.Member> addMember(@PathVariable UUID id,
			@Valid @RequestBody AccountViews.AddMember body) {
		AccountViews.Member member = this.accounts.addMember(id, body.email(), body.role());
		return ResponseEntity.created(URI.create("/api/organizations/" + id + "/members/" + member.userId()))
			.body(member);
	}

	@PutMapping("/api/organizations/{id}/members/{userId}")
	ResponseEntity<Void> changeRole(@PathVariable UUID id, @PathVariable UUID userId,
			@Valid @RequestBody AccountViews.ChangeRole body) {
		this.accounts.changeRole(id, userId, body.role());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/api/organizations/{id}/members/{userId}")
	ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
		this.accounts.removeMember(id, userId);
		return ResponseEntity.noContent().build();
	}

}
