package com.seatmap.api.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Users, organizations and memberships. */
@Repository
class AccountRepository {

	private final JdbcClient jdbcClient;

	AccountRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	void insertUser(UUID id, String email, String displayName, String passwordHash, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO app_user (id, email, display_name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)""")
			.params(id, email, displayName, passwordHash, Timestamp.from(now))
			.update();
	}

	boolean emailExists(String email) {
		return this.jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(email) = lower(?))")
			.param(email)
			.query(Boolean.class)
			.single();
	}

	Optional<UserRow> findUserByEmail(String email) {
		return this.jdbcClient.sql("""
				SELECT id, email, display_name, password_hash, locked_until FROM app_user
				WHERE lower(email) = lower(?)""")
			.param(email)
			.query((rs, row) -> new UserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
					rs.getString(4), (rs.getTimestamp(5) != null) ? rs.getTimestamp(5).toInstant() : null))
			.optional();
	}

	/**
	 * Counts a failed login. The one that reaches {@code maxFailures} locks the account until
	 * {@code lockedUntil} and starts the count again, all in one statement so parallel
	 * attempts can't slip past the limit.
	 */
	void recordFailedLogin(UUID userId, int maxFailures, Instant lockedUntil) {
		this.jdbcClient.sql("""
				UPDATE app_user SET
					locked_until = CASE WHEN failed_logins + 1 >= ? THEN ? ELSE locked_until END,
					failed_logins = CASE WHEN failed_logins + 1 >= ? THEN 0 ELSE failed_logins + 1 END
				WHERE id = ?""")
			.params(maxFailures, Timestamp.from(lockedUntil), maxFailures, userId)
			.update();
	}

	void clearFailedLogins(UUID userId) {
		this.jdbcClient.sql("""
				UPDATE app_user SET failed_logins = 0, locked_until = NULL
				WHERE id = ? AND (failed_logins <> 0 OR locked_until IS NOT NULL)""")
			.param(userId)
			.update();
	}

	void insertOrganization(UUID id, String name, Instant now) {
		this.jdbcClient.sql("INSERT INTO organization (id, name, created_at) VALUES (?, ?, ?)")
			.params(id, name, Timestamp.from(now))
			.update();
	}

	/** Locks the organization, so member changes (such as removing the last admin) happen one at a time. */
	boolean lockOrganization(UUID id) {
		return !this.jdbcClient.sql("SELECT id FROM organization WHERE id = ? FOR UPDATE")
			.param(id)
			.query(UUID.class)
			.list()
			.isEmpty();
	}

	void insertMembership(UUID organizationId, UUID userId, Role role, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO membership (organization_id, user_id, role, created_at) VALUES (?, ?, ?, ?)""")
			.params(organizationId, userId, role.name(), Timestamp.from(now))
			.update();
	}

	Optional<Role> findRole(UUID organizationId, UUID userId) {
		return this.jdbcClient.sql("SELECT role FROM membership WHERE organization_id = ? AND user_id = ?")
			.params(organizationId, userId)
			.query((rs, row) -> Role.valueOf(rs.getString(1)))
			.optional();
	}

	List<AccountViews.Membership> findMemberships(UUID userId) {
		return this.jdbcClient.sql("""
				SELECT o.id, o.name, m.role FROM membership m JOIN organization o ON o.id = m.organization_id
				WHERE m.user_id = ? ORDER BY o.name""")
			.param(userId)
			.query((rs, row) -> new AccountViews.Membership(rs.getObject(1, UUID.class), rs.getString(2),
					Role.valueOf(rs.getString(3))))
			.list();
	}

	List<AccountViews.Member> findMembers(UUID organizationId) {
		return this.jdbcClient.sql("""
				SELECT u.id, u.email, u.display_name, m.role FROM membership m JOIN app_user u ON u.id = m.user_id
				WHERE m.organization_id = ? ORDER BY u.display_name""")
			.param(organizationId)
			.query((rs, row) -> new AccountViews.Member(rs.getObject(1, UUID.class), rs.getString(2),
					rs.getString(3), Role.valueOf(rs.getString(4))))
			.list();
	}

	int countAdmins(UUID organizationId) {
		return this.jdbcClient.sql("SELECT count(*) FROM membership WHERE organization_id = ? AND role = 'ADMIN'")
			.param(organizationId)
			.query(Integer.class)
			.single();
	}

	void updateRole(UUID organizationId, UUID userId, Role role) {
		this.jdbcClient.sql("UPDATE membership SET role = ? WHERE organization_id = ? AND user_id = ?")
			.params(role.name(), organizationId, userId)
			.update();
	}

	void deleteMembership(UUID organizationId, UUID userId) {
		this.jdbcClient.sql("DELETE FROM membership WHERE organization_id = ? AND user_id = ?")
			.params(organizationId, userId)
			.update();
	}

	record UserRow(UUID id, String email, String displayName, String passwordHash, Instant lockedUntil) {

		SeatmapUser toUser() {
			return new SeatmapUser(this.id, this.email, this.displayName);
		}

	}

}
