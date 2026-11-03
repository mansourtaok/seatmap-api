package com.seatmap.api.layout;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A seat map and its editable draft, the designer document as saved by the designer. */
@Entity
public class Layout {

	@Id
	private UUID id;

	private String name;

	private String description;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "draft_document")
	private String draftDocument;

	private int draftSchemaVersion;

	/** Doubles as the draft's ETag: a save based on an older revision is rejected. */
	@Version
	private int draftRevision;

	private Instant createdAt;

	private Instant updatedAt;

	protected Layout() {
	}

	Layout(UUID id, Instant now) {
		this.id = id;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void replaceDraft(String name, String description, String document, int schemaVersion, Instant now) {
		this.name = name;
		this.description = description;
		this.draftDocument = document;
		this.draftSchemaVersion = schemaVersion;
		this.updatedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getDraftDocument() {
		return this.draftDocument;
	}

	public int getDraftSchemaVersion() {
		return this.draftSchemaVersion;
	}

	public int getDraftRevision() {
		return this.draftRevision;
	}

}
