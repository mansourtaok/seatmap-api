package com.seatmap.api.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.seatmap.api.ai.AiModelClient.TokenUsage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes one {@code ai_usage} row per call to the model, failed calls included, with its
 * token counts and estimated cost. This is what per-plan AI allowances will be built on.
 */
@Repository
class AiUsageRecorder {

	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	private final JdbcClient jdbcClient;

	private final AiProperties properties;

	AiUsageRecorder(JdbcClient jdbcClient, AiProperties properties) {
		this.jdbcClient = jdbcClient;
		this.properties = properties;
	}

	enum Status {

		SUCCESS, NEEDS_CLARIFICATION, CANNOT_DO, REFUSED, INVALID_RESPONSE, ERROR

	}

	record Call(UUID organizationId, UUID userId, String conversationId, UUID layoutId, String model,
			TokenUsage usage, long durationMs, Status status, String error, Instant at) {
	}

	void record(Call call) {
		this.jdbcClient.sql("""
				INSERT INTO ai_usage (id, organization_id, user_id, conversation_id, layout_id, model, input_tokens,
					output_tokens, cache_read_tokens, cache_write_tokens, estimated_cost_usd, duration_ms, status,
					error, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(UUID.randomUUID(), call.organizationId(), call.userId(), call.conversationId(), call.layoutId(),
					call.model(), (int) call.usage().input(), (int) call.usage().output(),
					(int) call.usage().cacheRead(), (int) call.usage().cacheWrite(), estimatedCost(call.usage()),
					(int) call.durationMs(), call.status().name(), truncate(call.error()), Timestamp.from(call.at()))
			.update();
	}

	/** What an organization's assistant calls cost since {@code since}, in US dollars. */
	BigDecimal spentSince(UUID organizationId, Instant since) {
		return this.jdbcClient
			.sql("SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM ai_usage WHERE organization_id = ? AND created_at >= ?")
			.params(organizationId, Timestamp.from(since))
			.query(BigDecimal.class)
			.single();
	}

	/** US dollars, from the configured per-million-token prices. */
	BigDecimal estimatedCost(TokenUsage usage) {
		AiProperties.Prices prices = this.properties.prices();
		return prices.input()
			.multiply(BigDecimal.valueOf(usage.input()))
			.add(prices.output().multiply(BigDecimal.valueOf(usage.output())))
			.add(prices.cacheRead().multiply(BigDecimal.valueOf(usage.cacheRead())))
			.add(prices.cacheWrite().multiply(BigDecimal.valueOf(usage.cacheWrite())))
			.divide(MILLION, 6, RoundingMode.HALF_UP);
	}

	private static String truncate(String error) {
		return (error == null || error.length() <= 500) ? error : error.substring(0, 500);
	}

}
