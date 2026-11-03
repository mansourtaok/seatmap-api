package com.seatmap.api.ticket;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
class MailConfig {

	@Bean
	MailDelivery mailDelivery(TicketProperties properties, ObjectProvider<JavaMailSender> mailSender) {
		TicketProperties.Mail mail = properties.mail();
		return switch (mail.delivery()) {
			case "file" -> new MailDelivery.ToDirectory(mail.directory());
			case "smtp" -> new MailDelivery.Smtp(mailSender.getIfAvailable(() -> {
				throw new IllegalStateException("seatmap.tickets.mail.delivery=smtp needs spring.mail.host");
			}));
			default -> throw new IllegalStateException(
					"seatmap.tickets.mail.delivery must be 'file' or 'smtp', not '" + mail.delivery() + "'");
		};
	}

}
