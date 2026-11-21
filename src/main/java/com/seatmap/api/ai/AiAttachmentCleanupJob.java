package com.seatmap.api.ai;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes assistant attachments past their retention period, every hour. */
@Component
class AiAttachmentCleanupJob {

	private static final Logger logger = LoggerFactory.getLogger(AiAttachmentCleanupJob.class);

	private final AiAttachments attachments;

	AiAttachmentCleanupJob(AiAttachments attachments) {
		this.attachments = attachments;
	}

	@Scheduled(fixedDelayString = "${seatmap.ai.attachment-cleanup-interval:1h}",
			initialDelayString = "${seatmap.ai.attachment-cleanup-interval:1h}")
	void deleteExpired() {
		int deleted = this.attachments.deleteExpired(Instant.now());
		if (deleted > 0) {
			logger.info("Deleted {} expired AI assistant attachments", deleted);
		}
	}

}
