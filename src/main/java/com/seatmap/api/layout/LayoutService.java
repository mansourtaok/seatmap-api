package com.seatmap.api.layout;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.seatmap.api.SeatmapProperties;
import com.seatmap.api.account.Access;
import com.seatmap.api.account.Role;
import com.seatmap.api.web.ConflictException;
import com.seatmap.api.web.InvalidRequestException;
import com.seatmap.api.web.NotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Layouts: an editable draft each, published into numbered versions that never change.
 * Publishing is also when a version's inventory (seats and areas for sale) is extracted.
 */
@Service
public class LayoutService {

	/** Browsers render these safely as images; SVG is left out because it can carry scripts. */
	private static final Set<String> ASSET_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

	private static final Pattern ASSET_ID = Pattern.compile("[A-Za-z0-9._-]{1,200}");

	private final LayoutRepository layouts;

	private final LayoutVersionRepository versions;

	private final InventoryRepository inventory;

	private final AssetRepository assets;

	private final JsonMapper jsonMapper;

	private final SeatmapProperties properties;

	private final Access access;

	LayoutService(LayoutRepository layouts, LayoutVersionRepository versions, InventoryRepository inventory,
			AssetRepository assets, JsonMapper jsonMapper, SeatmapProperties properties, Access access) {
		this.layouts = layouts;
		this.versions = versions;
		this.inventory = inventory;
		this.assets = assets;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
		this.access = access;
	}

	/**
	 * Stores a new layout in an organization. Its id is assigned here and written into the
	 * document. {@code organizationId} may be left out by someone in just one organization.
	 */
	@Transactional
	public Layout create(JsonNode document, UUID organizationId) {
		UUID organization = (organizationId != null) ? organizationId : onlyOrganization();
		this.access.require(organization, Role.EDITOR, () -> new NotFoundException("No organization " + organization));
		Instant now = now();
		Layout layout = new Layout(UUID.randomUUID(), organization, now);
		applyDraft(layout, document, now);
		return this.layouts.saveAndFlush(layout);
	}

	/** The layouts of the current user's organizations, most recently changed first. */
	@Transactional(readOnly = true)
	public List<LayoutSummary> list() {
		List<UUID> organizations = this.access.organizationIds();
		return organizations.isEmpty() ? List.of() : this.layouts.findSummaries(organizations);
	}

	@Transactional(readOnly = true)
	public Layout get(UUID id) {
		return find(id, Role.VIEWER);
	}

	/** The layout, if the current user has at least {@code role} in its organization. */
	@Transactional(readOnly = true)
	public Layout find(UUID id, Role role) {
		Layout layout = this.layouts.findById(id).orElseThrow(() -> layoutNotFound(id));
		this.access.require(layout.getOrganizationId(), role, () -> layoutNotFound(id));
		return layout;
	}

	/** Replaces the draft, provided it is still at {@code expectedRevision}. */
	@Transactional
	public Layout saveDraft(UUID id, int expectedRevision, JsonNode document) {
		Layout layout = find(id, Role.EDITOR);
		requireRevision(layout, expectedRevision);
		JsonNode documentId = document.path("id");
		if (documentId.isString() && !documentId.asString().equals(id.toString())) {
			throw new InvalidRequestException("The document's id is %s, not this layout's (%s)"
				.formatted(documentId.asString(), id));
		}
		applyDraft(layout, document, now());
		// Flushing bumps the revision now, so the caller gets the new ETag.
		return this.layouts.saveAndFlush(layout);
	}

	/** Deletes a layout that was never published; published versions may be selling tickets. */
	@Transactional
	public void delete(UUID id) {
		Layout layout = this.layouts.findForUpdate(id).orElseThrow(() -> layoutNotFound(id));
		this.access.require(layout.getOrganizationId(), Role.ADMIN, () -> layoutNotFound(id));
		if (this.versions.existsByLayoutId(id)) {
			throw new ConflictException("This layout has published versions, so it can't be deleted");
		}
		this.layouts.delete(layout);
	}

	/**
	 * Publishes the draft as the next version. With {@code expectedRevision}, refuses if the
	 * draft changed since the caller last saw it, so what gets published is what they reviewed.
	 */
	@Transactional
	public VersionSummary publish(UUID id, Integer expectedRevision) {
		Layout layout = this.layouts.findForUpdate(id).orElseThrow(() -> layoutNotFound(id));
		this.access.require(layout.getOrganizationId(), Role.EDITOR, () -> layoutNotFound(id));
		if (expectedRevision != null) {
			requireRevision(layout, expectedRevision);
		}
		JsonNode document = this.jsonMapper.readTree(layout.getDraftDocument());
		// The draft passed these checks when saved, but the rules may have tightened since.
		requireValid(document);
		Inventory extracted = InventoryExtractor.extract(document);
		LayoutVersion version = new LayoutVersion(UUID.randomUUID(), id,
				this.versions.findLatestVersionNumber(id) + 1, layout.getDraftDocument(),
				layout.getDraftSchemaVersion(), extracted.seatCount(), extracted.totalCapacity(), now());
		// Flush first: the inventory rows reference the version row.
		this.versions.saveAndFlush(version);
		this.inventory.insert(version.getId(), extracted);
		return version.toSummary();
	}

	@Transactional(readOnly = true)
	public List<VersionSummary> versions(UUID id) {
		find(id, Role.VIEWER);
		return this.versions.findSummaries(id);
	}

	@Transactional(readOnly = true)
	public LayoutVersion version(UUID id, int versionNumber) {
		find(id, Role.VIEWER);
		return this.versions.findByLayoutIdAndVersionNumber(id, versionNumber)
			.orElseThrow(() -> new NotFoundException("Layout %s has no version %d".formatted(id, versionNumber)));
	}

	@Transactional(readOnly = true)
	public Inventory inventory(UUID id, int versionNumber) {
		return this.inventory.find(version(id, versionNumber).getId());
	}

	/**
	 * Stores a floor-plan image. Assets never change once stored, because published versions
	 * show them: storing the same bytes again is accepted, different bytes are refused.
	 *
	 * @return true if the asset was created, false if it was already there
	 */
	@Transactional
	public boolean putAsset(UUID id, String assetId, String mimeType, byte[] bytes) {
		find(id, Role.EDITOR);
		if (!ASSET_ID.matcher(assetId).matches()) {
			throw new InvalidRequestException("Asset ids may only use letters, digits, '.', '_' and '-'");
		}
		if (mimeType == null || !ASSET_TYPES.contains(mimeType)) {
			throw new InvalidRequestException("Floor plans must be PNG, JPEG, WebP or GIF, not " + mimeType);
		}
		if (bytes.length == 0 || bytes.length > this.properties.maxAssetSize().toBytes()) {
			throw new InvalidRequestException("Floor plans must be between 1 byte and " + this.properties.maxAssetSize());
		}
		String sha256 = sha256(bytes);
		Optional<String> existing = this.assets.findSha256(id, assetId);
		if (existing.isPresent()) {
			if (!existing.get().equals(sha256)) {
				throw new ConflictException("Asset " + assetId + " already exists with different content");
			}
			return false;
		}
		this.assets.insert(id, assetId, mimeType, sha256, bytes, now());
		return true;
	}

	@Transactional(readOnly = true)
	public StoredAsset asset(UUID id, String assetId) {
		find(id, Role.VIEWER);
		return this.assets.find(id, assetId)
			.orElseThrow(() -> new NotFoundException("Layout %s has no asset %s".formatted(id, assetId)));
	}

	private void applyDraft(Layout layout, JsonNode document, Instant now) {
		requireValid(document);
		ObjectNode draft = (ObjectNode) document;
		draft.put("id", layout.getId().toString());
		String json = this.jsonMapper.writeValueAsString(draft);
		if (json.length() > this.properties.maxDocumentSize().toBytes()) {
			throw new InvalidRequestException("The layout document is larger than " + this.properties.maxDocumentSize());
		}
		JsonNode description = draft.path("description");
		layout.replaceDraft(draft.get("name").asString(), description.isString() ? description.asString() : null, json,
				draft.get("schemaVersion").asInt(), now);
	}

	private UUID onlyOrganization() {
		List<UUID> organizations = this.access.organizationIds();
		if (organizations.size() != 1) {
			throw new InvalidRequestException(organizations.isEmpty() ? "You aren't in any organization"
					: "You're in several organizations; say which one with ?organizationId=");
		}
		return organizations.getFirst();
	}

	private static void requireValid(JsonNode document) {
		List<String> problems = LayoutDocumentValidator.validate(document);
		if (!problems.isEmpty()) {
			throw new InvalidRequestException("The layout document is invalid", problems);
		}
	}

	private static void requireRevision(Layout layout, int expectedRevision) {
		if (layout.getDraftRevision() != expectedRevision) {
			throw new ConflictException("The layout is at revision %d, not %d; reload it before saving"
				.formatted(layout.getDraftRevision(), expectedRevision));
		}
	}

	private static NotFoundException layoutNotFound(UUID id) {
		return new NotFoundException("No layout " + id);
	}

	/** Postgres keeps microseconds; truncating means what's returned equals what's stored. */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
