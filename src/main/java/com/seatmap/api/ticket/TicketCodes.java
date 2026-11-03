package com.seatmap.api.ticket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The code in a ticket's QR: {@code SM1.<ticket id>.<signature>}, the signature being an
 * HMAC-SHA256 of the id under the server's ticket key. Codes can't be forged without the
 * key, and needn't be stored: any ticket's code can be made again from its id.
 */
@Component
public class TicketCodes {

	private static final String PREFIX = "SM1";

	private static final int SIGNATURE_BYTES = 16;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;

	@Autowired
	TicketCodes(TicketProperties properties) {
		this(properties.key());
	}

	TicketCodes(String key) {
		if (key == null || key.length() < 32) {
			throw new IllegalStateException(
					"seatmap.tickets.key (SEATMAP_TICKET_KEY) must be set to a secret of at least 32 characters");
		}
		this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	public String codeFor(UUID ticketId) {
		byte[] id = bytes(ticketId);
		return PREFIX + "." + ENCODER.encodeToString(id) + "." + ENCODER.encodeToString(sign(id));
	}

	/** The ticket id in a genuine code; empty for anything forged, altered or malformed. */
	public Optional<UUID> verify(String code) {
		if (code == null) {
			return Optional.empty();
		}
		String[] parts = code.strip().split("\\.");
		if (parts.length != 3 || !parts[0].equals(PREFIX)) {
			return Optional.empty();
		}
		try {
			byte[] id = DECODER.decode(parts[1]);
			byte[] signature = DECODER.decode(parts[2]);
			// Base64's last character has spare bits the decoder ignores; accept only the one
			// spelling we issue, so each ticket has exactly one valid code.
			if (id.length != 16 || !ENCODER.encodeToString(id).equals(parts[1])
					|| !MessageDigest.isEqual(signature, sign(id))
					|| !ENCODER.encodeToString(signature).equals(parts[2])) {
				return Optional.empty();
			}
			ByteBuffer buffer = ByteBuffer.wrap(id);
			return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private byte[] sign(byte[] id) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.key);
			mac.update(PREFIX.getBytes(StandardCharsets.US_ASCII));
			return Arrays.copyOf(mac.doFinal(id), SIGNATURE_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}

}
