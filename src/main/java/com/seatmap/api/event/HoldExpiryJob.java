package com.seatmap.api.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases expired holds every 30 seconds. */
@Component
class HoldExpiryJob {

	private static final Logger logger = LoggerFactory.getLogger(HoldExpiryJob.class);

	private final ReservationService reservations;

	HoldExpiryJob(ReservationService reservations) {
		this.reservations = reservations;
	}

	@Scheduled(fixedDelayString = "${seatmap.hold-expiry-interval:30s}")
	void releaseExpired() {
		int freed = this.reservations.releaseExpired();
		if (freed > 0) {
			logger.info("Released {} seats from expired holds", freed);
		}
	}

}
