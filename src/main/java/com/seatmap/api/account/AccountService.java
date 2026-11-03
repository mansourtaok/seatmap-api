package com.seatmap.api.account;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.seatmap.api.SeatmapProperties;
import com.seatmap.api.web.ConflictException;
import com.seatmap.api.web.InvalidRequestException;
import com.seatmap.api.web.NotFoundException;
import com.seatmap.api.web.UnauthorizedException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Signing up, logging in, and managing organizations and their members. */
@Service
public class AccountService {

	private static final String WRONG_LOGIN = "The email or password is wrong";

	private final AccountRepository accounts;

	private final Access access;

	private final PasswordEncoder passwordEncoder;

	private final SeatmapProperties properties;

	/** Checked against when the email is unknown, so a login takes as long either way. */
	private final String unknownUserHash;

	AccountService(AccountRepository accounts, Access access, PasswordEncoder passwordEncoder,
			SeatmapProperties properties) {
		this.accounts = accounts;
		this.access = access;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/** Creates a user and their organization, with them as its admin. */
	@Transactional
	public SeatmapUser register(AccountViews.Register request) {
		String email = request.email().strip();
		if (this.accounts.emailExists(email)) {
			throw new ConflictException("An account with this email already exists");
		}
		Instant now = now();
		UUID userId = UUID.randomUUID();
		try {
			this.accounts.insertUser(userId, email, request.displayName().strip(),
					this.passwordEncoder.encode(request.password()), now);
		}
		catch (DuplicateKeyException ex) {
			// Someone signed up with the same email at the same moment.
			throw new ConflictException("An account with this email already exists");
		}
		createOrganization(userId, request.organizationName().strip(), now);
		return new SeatmapUser(userId, email, request.displayName().strip());
	}

	/**
	 * Checks an email and password. Too many failures in a row lock the account for a while,
	 * even for the right password. Runs in its own transaction so a failure is counted even
	 * though the login is refused.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = UnauthorizedException.class)
	public SeatmapUser login(String email, String password) {
		AccountRepository.UserRow user = this.accounts.findUserByEmail(email.strip()).orElse(null);
		if (user == null) {
			this.passwordEncoder.matches(password, this.unknownUserHash);
			throw new UnauthorizedException(WRONG_LOGIN);
		}
		Instant now = now();
		if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
			long minutes = Math.max(1, Duration.between(now, user.lockedUntil()).toMinutes() + 1);
			throw new UnauthorizedException(
					"Too many failed logins; try again in %d minute%s".formatted(minutes, (minutes == 1) ? "" : "s"));
		}
		if (!this.passwordEncoder.matches(password, user.passwordHash())) {
			this.accounts.recordFailedLogin(user.id(), this.properties.maxFailedLogins(),
					now.plus(this.properties.loginLockDuration()));
			throw new UnauthorizedException(WRONG_LOGIN);
		}
		this.accounts.clearFailedLogins(user.id());
		return user.toUser();
	}

	@Transactional(readOnly = true)
	public AccountViews.Me me() {
		SeatmapUser user = this.access.requireUser();
		return new AccountViews.Me(user.id(), user.email(), user.displayName(), this.access.memberships());
	}

	/** Creates another organization, with the current user as its admin. */
	@Transactional
	public AccountViews.Membership createOrganization(String name) {
		UUID id = createOrganization(this.access.requireUser().id(), name.strip(), now());
		return new AccountViews.Membership(id, name.strip(), Role.ADMIN);
	}

	@Transactional(readOnly = true)
	public List<AccountViews.Member> members(UUID organizationId) {
		this.access.require(organizationId, Role.VIEWER, () -> organizationNotFound(organizationId));
		return this.accounts.findMembers(organizationId);
	}

	/** Adds someone who already has an account. Inviting by email comes with the email service. */
	@Transactional
	public AccountViews.Member addMember(UUID organizationId, String email, Role role) {
		this.access.require(organizationId, Role.ADMIN, () -> organizationNotFound(organizationId));
		AccountRepository.UserRow user = this.accounts.findUserByEmail(email.strip())
			.orElseThrow(() -> new NotFoundException("Nobody has an account with this email yet"));
		if (this.accounts.findRole(organizationId, user.id()).isPresent()) {
			throw new ConflictException(user.email() + " is already a member");
		}
		this.accounts.insertMembership(organizationId, user.id(), role, now());
		return new AccountViews.Member(user.id(), user.email(), user.displayName(), role);
	}

	@Transactional
	public void changeRole(UUID organizationId, UUID userId, Role role) {
		this.access.require(organizationId, Role.ADMIN, () -> organizationNotFound(organizationId));
		this.accounts.lockOrganization(organizationId);
		Role current = this.accounts.findRole(organizationId, userId)
			.orElseThrow(() -> new NotFoundException("This person isn't a member"));
		if (current == Role.ADMIN && role != Role.ADMIN) {
			requireAnotherAdmin(organizationId);
		}
		this.accounts.updateRole(organizationId, userId, role);
	}

	/** Removes a member. Admins can remove anyone; anyone can leave. */
	@Transactional
	public void removeMember(UUID organizationId, UUID userId) {
		SeatmapUser user = this.access.requireUser();
		Role required = user.id().equals(userId) ? Role.VIEWER : Role.ADMIN;
		this.access.require(organizationId, required, () -> organizationNotFound(organizationId));
		this.accounts.lockOrganization(organizationId);
		Role current = this.accounts.findRole(organizationId, userId)
			.orElseThrow(() -> new NotFoundException("This person isn't a member"));
		if (current == Role.ADMIN) {
			requireAnotherAdmin(organizationId);
		}
		this.accounts.deleteMembership(organizationId, userId);
	}

	private UUID createOrganization(UUID userId, String name, Instant now) {
		if (name.isBlank()) {
			throw new InvalidRequestException("The organization needs a name");
		}
		UUID id = UUID.randomUUID();
		this.accounts.insertOrganization(id, name, now);
		this.accounts.insertMembership(id, userId, Role.ADMIN, now);
		return id;
	}

	private void requireAnotherAdmin(UUID organizationId) {
		if (this.accounts.countAdmins(organizationId) <= 1) {
			throw new ConflictException("An organization needs at least one admin; make someone else admin first");
		}
	}

	private static NotFoundException organizationNotFound(UUID id) {
		return new NotFoundException("No organization " + id);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
