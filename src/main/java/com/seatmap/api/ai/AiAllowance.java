package com.seatmap.api.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Each organization's monthly AI allowance: how much of it is used, and stopping the
 * assistant once it's all used. Months are calendar months in UTC. The allowance is one
 * setting today ({@code seatmap.ai.monthly-allowance}); with subscription plans it will
 * come from the organization's plan, here.
 */
@Component
class AiAllowance {

	private final AiProperties properties;

	private final AiUsageRecorder usage;

	AiAllowance(AiProperties properties, AiUsageRecorder usage) {
		this.properties = properties;
		this.usage = usage;
	}

	/**
	 * @param percent share of this month's allowance used, 0 to 100 (whole numbers, rounded down,
	 * so 100 means used up)
	 * @param resetsAt when the next month's allowance starts
	 */
	record Usage(int percent, Instant resetsAt) {
	}

	Usage usage(UUID organizationId) {
		LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
		BigDecimal spent = this.usage.spentSince(organizationId, month.atStartOfDay(ZoneOffset.UTC).toInstant());
		BigDecimal allowance = this.properties.monthlyAllowance();
		int percent = (allowance.signum() <= 0) ? 100
				: spent.multiply(BigDecimal.valueOf(100)).divide(allowance, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
		return new Usage(percent, month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	/** Refuses (402) when the organization has used this month's allowance. */
	void requireAvailable(UUID organizationId) {
		Usage usage = usage(organizationId);
		if (usage.percent() >= 100) {
			throw new AiAllowanceUsedException(
					"This month's AI assistant allowance is used up; it renews on " + usage.resetsAt().toString().substring(0, 10),
					usage.resetsAt());
		}
	}

}
