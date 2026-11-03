package com.seatmap.api.layout;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.seatmap.api.web.InvalidRequestException;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layout documents, their published versions and floor-plan images. A draft's revision is
 * its ETag: send it back in {@code If-Match} when saving, and a stale save gets 409.
 */
@RestController
@RequestMapping("/api/layouts")
class LayoutController {

	private final LayoutService service;

	LayoutController(LayoutService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<String> create(@RequestBody JsonNode document) {
		Layout layout = this.service.create(document);
		return ResponseEntity.created(URI.create("/api/layouts/" + layout.getId()))
			.eTag(eTag(layout.getDraftRevision()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(layout.getDraftDocument());
	}

	@GetMapping
	List<LayoutSummary> list() {
		return this.service.list();
	}

	@GetMapping("/{id}")
	ResponseEntity<String> draft(@PathVariable UUID id) {
		Layout layout = this.service.get(id);
		return ResponseEntity.ok()
			.eTag(eTag(layout.getDraftRevision()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(layout.getDraftDocument());
	}

	@PutMapping("/{id}/draft")
	ResponseEntity<Void> saveDraft(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
			@RequestBody JsonNode document) {
		Layout layout = this.service.saveDraft(id, revision(ifMatch), document);
		return ResponseEntity.noContent().eTag(eTag(layout.getDraftRevision())).build();
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable UUID id) {
		this.service.delete(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/versions")
	ResponseEntity<VersionSummary> publish(@PathVariable UUID id,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		VersionSummary version = this.service.publish(id, (ifMatch != null) ? revision(ifMatch) : null);
		return ResponseEntity.created(URI.create("/api/layouts/" + id + "/versions/" + version.version()))
			.body(version);
	}

	@GetMapping("/{id}/versions")
	List<VersionSummary> versions(@PathVariable UUID id) {
		return this.service.versions(id);
	}

	@GetMapping("/{id}/versions/{version}")
	ResponseEntity<String> version(@PathVariable UUID id, @PathVariable int version) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(this.service.version(id, version).getDocument());
	}

	@GetMapping("/{id}/versions/{version}/inventory")
	Inventory inventory(@PathVariable UUID id, @PathVariable int version) {
		return this.service.inventory(id, version);
	}

	@PutMapping("/{id}/assets/{assetId}")
	ResponseEntity<Void> putAsset(@PathVariable UUID id, @PathVariable String assetId,
			@RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
			@RequestBody byte[] bytes) {
		boolean created = this.service.putAsset(id, assetId, mimeType(contentType), bytes);
		return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
	}

	@GetMapping("/{id}/assets/{assetId}")
	ResponseEntity<byte[]> asset(@PathVariable UUID id, @PathVariable String assetId) {
		AssetRepository.StoredAsset asset = this.service.asset(id, assetId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(asset.mimeType()))
			.eTag(asset.sha256())
			.header("X-Content-Type-Options", "nosniff")
			.body(asset.bytes());
	}

	/** The type without parameters: {@code image/png;charset=UTF-8} is {@code image/png}. */
	private static String mimeType(String contentType) {
		if (contentType == null) {
			return null;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return mediaType.getType() + "/" + mediaType.getSubtype();
		}
		catch (InvalidMediaTypeException ex) {
			throw new InvalidRequestException("Content-Type is not a valid media type: " + contentType);
		}
	}

	private static String eTag(int revision) {
		return "\"" + revision + "\"";
	}

	/** Reads a revision from an If-Match value: {@code "3"}, {@code W/"3"} or a bare 3. */
	private static int revision(String ifMatch) {
		String value = ifMatch.strip();
		if (value.startsWith("W/")) {
			value = value.substring(2);
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex) {
			throw new InvalidRequestException("If-Match must be the layout's ETag, such as \"3\"");
		}
	}

}
