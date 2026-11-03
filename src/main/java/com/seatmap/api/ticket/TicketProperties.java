package com.seatmap.api.ticket;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code seatmap.tickets.*}.
 *
 * @param key secret that signs ticket codes, at least 32 characters; anyone who has it can
 * make valid tickets, so it comes from the environment ({@code SEATMAP_TICKET_KEY}), never git
 * @param mail how ticket emails are sent
 */
@ConfigurationProperties("seatmap.tickets")
public record TicketProperties(String key, @DefaultValue Mail mail) {

	/**
	 * @param delivery {@code file} writes each email as an .eml file into {@code directory}
	 * (for development); {@code smtp} sends it through the {@code spring.mail.*} server
	 * @param directory where {@code file} delivery writes
	 * @param from the sender address
	 */
	public record Mail(@DefaultValue("file") String delivery, @DefaultValue("build/mail") Path directory,
			@DefaultValue("seatmap <tickets@seatmap.local>") String from) {
	}

}
