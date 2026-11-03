package com.seatmap.api.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/** An event selling one published layout version. */
@Entity
public class Event {

	@Id
	private UUID id;

	private UUID organizationId;

	private UUID layoutId;

	private UUID versionId;

	private int versionNumber;

	private String name;

	private Instant startsAt;

	private Instant endsAt;

	private String currency;

	private String timeZone;

	@Enumerated(EnumType.STRING)
	private EventStatus status;

	/** The event's ETag: an update based on an older revision is rejected. */
	@Version
	private int revision;

	private Instant createdAt;

	private Instant updatedAt;

	protected Event() {
	}

	Event(UUID id, UUID organizationId, UUID layoutId, UUID versionId, int versionNumber, Instant now) {
		this.id = id;
		this.organizationId = organizationId;
		this.layoutId = layoutId;
		this.versionId = versionId;
		this.versionNumber = versionNumber;
		this.status = EventStatus.DRAFT;
		this.createdAt = now;
	}

	void update(String name, Instant startsAt, Instant endsAt, String currency, String timeZone, EventStatus status,
			Instant now) {
		this.name = name;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.currency = currency;
		this.timeZone = timeZone;
		this.status = status;
		this.updatedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getOrganizationId() {
		return this.organizationId;
	}

	public UUID getLayoutId() {
		return this.layoutId;
	}

	public UUID getVersionId() {
		return this.versionId;
	}

	public int getVersionNumber() {
		return this.versionNumber;
	}

	public String getName() {
		return this.name;
	}

	public Instant getStartsAt() {
		return this.startsAt;
	}

	public Instant getEndsAt() {
		return this.endsAt;
	}

	public String getCurrency() {
		return this.currency;
	}

	public String getTimeZone() {
		return this.timeZone;
	}

	public EventStatus getStatus() {
		return this.status;
	}

	public int getRevision() {
		return this.revision;
	}

}
