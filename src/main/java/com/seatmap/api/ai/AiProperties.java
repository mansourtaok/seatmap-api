package com.seatmap.api.ai;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under {@code seatmap.ai.*}. The Anthropic API key is not one of them: the SDK
 * reads it from the {@code ANTHROPIC_API_KEY} environment variable, so it never sits in a
 * file or in git.
 *
 * @param enabled whether the assistant is offered at all (off by default: it costs money per call)
 * @param model the Claude model to call
 * @param effort how hard Claude thinks: low, medium, high, xhigh or max
 * @param maxTokens most tokens one answer may use, thinking included
 * @param timeout longest one call may take before the user gets "try again"
 * @param requestsPerMinute most assistant requests one user may make per minute
 * @param maxRequestSize largest request accepted, venue summary and history included
 * @param prices what the model costs, for the usage records' estimated cost
 * @param monthlyAllowance what one organization may spend on the assistant per calendar month (UTC),
 * in US dollars at {@code prices}; reached, the assistant stops for it until the next month.
 * Organizers see only the percentage used. One amount for every organization until plans exist.
 * @param attachments limits on the photos and PDFs organizers attach to a conversation
 */
@ConfigurationProperties("seatmap.ai")
public record AiProperties(@DefaultValue("false") boolean enabled, @DefaultValue("claude-opus-5-5") String model,
		@DefaultValue("high") String effort, @DefaultValue("32000") long maxTokens,
		@DefaultValue("3m") Duration timeout, @DefaultValue("10") int requestsPerMinute,
		@DefaultValue("1MB") DataSize maxRequestSize, @DefaultValue Prices prices,
		@DefaultValue("5.00") BigDecimal monthlyAllowance, @DefaultValue Attachments attachments) {

	/**
	 * Every image and PDF page is read by Claude on each request of the conversation (cached
	 * after the first, at a tenth of the price), so these keep both cost and request size in check.
	 * @param maxPerConversation most files one conversation may have
	 * @param maxImageSize largest image (Claude accepts up to 5 MB; the designer sends about 1 MB)
	 * @param maxPdfSize largest PDF
	 * @param maxTotalSize most bytes of files in one conversation (a request to Claude may be 32 MB,
	 * and files grow by a third when encoded for it)
	 * @param maxPdfPages most pages in one PDF
	 * @param uploadsPerMinute most uploads one user may make per minute
	 * @param retention how long a file is kept; conversations live in the designer's memory, so a day is plenty
	 */
	public record Attachments(@DefaultValue("10") int maxPerConversation, @DefaultValue("5MB") DataSize maxImageSize,
			@DefaultValue("10MB") DataSize maxPdfSize, @DefaultValue("20MB") DataSize maxTotalSize,
			@DefaultValue("20") int maxPdfPages, @DefaultValue("30") int uploadsPerMinute,
			@DefaultValue("24h") Duration retention) {
	}

	/**
	 * US dollars per million tokens. Defaults are Claude Opus 5.5's list prices; change them
	 * with the model.
	 */
	public record Prices(@DefaultValue("4.00") BigDecimal input, @DefaultValue("20.00") BigDecimal output,
			@DefaultValue("0.20") BigDecimal cacheRead, @DefaultValue("5.00") BigDecimal cacheWrite) {
	}

}
