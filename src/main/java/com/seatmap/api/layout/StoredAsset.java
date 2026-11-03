package com.seatmap.api.layout;

/** A floor-plan image as stored: its type, SHA-256 (used as its ETag) and bytes. */
public record StoredAsset(String mimeType, String sha256, byte[] bytes) {
}
