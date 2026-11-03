package com.seatmap.api.ticket;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.javamail.JavaMailSender;

/** Hands a finished email to whatever delivers it. */
interface MailDelivery {

	void deliver(MimeMessage message) throws MessagingException, IOException;

	/** Development: writes each email as an .eml file, which any mail program opens. */
	record ToDirectory(Path directory) implements MailDelivery {

		@Override
		public void deliver(MimeMessage message) throws MessagingException, IOException {
			Files.createDirectories(this.directory);
			Path file = this.directory.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".eml");
			try (OutputStream out = Files.newOutputStream(file)) {
				message.writeTo(out);
			}
		}

	}

	/** Sends through the SMTP server configured with {@code spring.mail.*}. */
	record Smtp(JavaMailSender sender) implements MailDelivery {

		@Override
		public void deliver(MimeMessage message) {
			this.sender.send(message);
		}

	}

}
