package com.seatmap.api.layout;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Floor-plan image bytes, stored per layout under the designer's asset id. */
@Repository
class AssetRepository {

	private final JdbcClient jdbcClient;

	AssetRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	Optional<StoredAsset> find(UUID layoutId, String assetId) {
		return this.jdbcClient.sql("SELECT mime_type, sha256, bytes FROM asset WHERE layout_id = ? AND asset_id = ?")
			.params(layoutId, assetId)
			.query((rs, row) -> new StoredAsset(rs.getString(1), rs.getString(2), rs.getBytes(3)))
			.optional();
	}

	Optional<String> findSha256(UUID layoutId, String assetId) {
		return this.jdbcClient.sql("SELECT sha256 FROM asset WHERE layout_id = ? AND asset_id = ?")
			.params(layoutId, assetId)
			.query(String.class)
			.optional();
	}

	void insert(UUID layoutId, String assetId, String mimeType, String sha256, byte[] bytes, Instant now) {
		this.jdbcClient.sql("""
				INSERT INTO asset (layout_id, asset_id, mime_type, size_bytes, sha256, bytes, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)""")
			.params(layoutId, assetId, mimeType, bytes.length, sha256, bytes, Timestamp.from(now))
			.update();
	}

}
