package com.seatmap.api.layout;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published layouts as buyers see them. No access checks here: callers (the public event
 * pages) decide who may see a version, such as only while its event is on sale.
 */
@Component
public class PublishedLayouts {

	private final LayoutVersionRepository versions;

	private final AssetRepository assets;

	private final JsonMapper jsonMapper;

	PublishedLayouts(LayoutVersionRepository versions, AssetRepository assets, JsonMapper jsonMapper) {
		this.versions = versions;
		this.assets = assets;
		this.jsonMapper = jsonMapper;
	}

	/** The version's document, as stored JSON. */
	@Transactional(readOnly = true)
	public Optional<String> document(UUID versionId) {
		return this.versions.findById(versionId).map(LayoutVersion::getDocument);
	}

	/** An image the version's document lists among its assets; other assets of the layout stay private. */
	@Transactional(readOnly = true)
	public Optional<StoredAsset> asset(UUID versionId, String assetId) {
		return this.versions.findById(versionId).filter((version) -> {
			JsonNode assets = this.jsonMapper.readTree(version.getDocument()).path("assets");
			return assets.has(assetId);
		}).flatMap((version) -> this.assets.find(version.getLayoutId(), assetId));
	}

}
