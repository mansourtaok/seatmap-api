package com.seatmap.api.layout;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A published snapshot of a layout. Never changes once written. */
@Entity
@Immutable
public class LayoutVersion {

	@Id
	private UUID id;

	private UUID layoutId;

	private int versionNumber;

	@JdbcTypeCode(SqlTypes.JSON)
	private String document;

	private int schemaVersion;

	private int seatCount;

	private int totalCapacity;

	private Instant publishedAt;

	protected LayoutVersion() {
	}

	LayoutVersion(UUID id, UUID layoutId, int versionNumber, String document, int schemaVersion, int seatCount,
			int totalCapacity, Instant publishedAt) {
		this.id = id;
		this.layoutId = layoutId;
		this.versionNumber = versionNumber;
		this.document = document;
		this.schemaVersion = schemaVersion;
		this.seatCount = seatCount;
		this.totalCapacity = totalCapacity;
		this.publishedAt = publishedAt;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getLayoutId() {
		return this.layoutId;
	}

	public String getDocument() {
		return this.document;
	}

	VersionSummary toSummary() {
		return new VersionSummary(this.versionNumber, this.seatCount, this.totalCapacity, this.publishedAt);
	}

}
