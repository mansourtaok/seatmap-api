package com.seatmap.api.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.seatmap.api.web.ForbiddenException;
import com.seatmap.api.web.NotFoundException;
import com.seatmap.api.web.UnauthorizedException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who the current request is from, and what they may do in an organization. Something in an
 * organization the user isn't a member of is reported as not found rather than forbidden, so
 * nobody can find out which ids exist.
 */
@Component
public class Access {

	private final AccountRepository accounts;

	Access(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/** The logged-in user, or empty for a guest. */
	public Optional<SeatmapUser> currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null && authentication.getPrincipal() instanceof SeatmapUser user)
				? Optional.of(user) : Optional.empty();
	}

	public SeatmapUser requireUser() {
		return currentUser().orElseThrow(() -> new UnauthorizedException("Log in first"));
	}

	/** The current user's role in the organization; empty for guests and non-members. */
	public Optional<Role> roleIn(UUID organizationId) {
		return currentUser().flatMap((user) -> this.accounts.findRole(organizationId, user.id()));
	}

	/** Whether the current user is a member with at least {@code role}. */
	public boolean has(UUID organizationId, Role role) {
		return roleIn(organizationId).map((actual) -> actual.includes(role)).orElse(false);
	}

	/**
	 * Requires at least {@code role} in the organization: non-members get {@code notFound},
	 * members with a lesser role a 403.
	 */
	public void require(UUID organizationId, Role role, Supplier<NotFoundException> notFound) {
		Role actual = roleIn(organizationId).orElseThrow(notFound);
		if (!actual.includes(role)) {
			throw new ForbiddenException("This needs the %s role in the organization; you are %s".formatted(role,
					actual));
		}
	}

	/** The current user's organizations; empty for guests. */
	public List<AccountViews.Membership> memberships() {
		return currentUser().map((user) -> this.accounts.findMemberships(user.id())).orElse(List.of());
	}

	public List<UUID> organizationIds() {
		return memberships().stream().map(AccountViews.Membership::organizationId).toList();
	}

}
