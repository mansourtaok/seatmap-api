package com.seatmap.api;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under {@code seatmap.*}.
 *
 * @param maxDocumentSize largest layout document accepted (a 100,000-seat stadium is about 30 MB)
 * @param maxAssetSize largest floor-plan image accepted
 * @param corsAllowedOrigins browser origins allowed to call the API, such as the Angular dev server
 */
@ConfigurationProperties("seatmap")
public record SeatmapProperties(@DefaultValue("50MB") DataSize maxDocumentSize,
		@DefaultValue("20MB") DataSize maxAssetSize, @DefaultValue("http://localhost:4200") List<String> corsAllowedOrigins) {
}
