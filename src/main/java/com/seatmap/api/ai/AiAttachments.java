package com.seatmap.api.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.seatmap.api.ai.AiModelClient.Attachment;
import com.seatmap.api.web.InvalidRequestException;
import com.seatmap.api.web.NotFoundException;
import com.seatmap.api.web.PayloadTooLargeException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The files organizers attach to an assistant conversation: checked when uploaded (type from
 * the content, size, pages), kept for a day, and only readable by the same user in the same
 * conversation.
 */
@Repository
class AiAttachments {

	/** Claude reads images up to 8000 pixels on a side (and scales anything over 2576 down). */
	static final int MAX_IMAGE_SIDE = 8000;

	private static final int MAX_NAME_LENGTH = 120;

	private final JdbcClient jdbcClient;

	private final AiProperties.Attachments limits;

	AiAttachments(JdbcClient jdbcClient, AiProperties properties) {
		this.jdbcClient = jdbcClient;
		this.limits = properties.attachments();
	}

	/**
	 * What the designer is told about a stored file.
	 * @param pages a PDF's page count; null for an image
	 */
	record Info(UUID id, String name, String mediaType, long size, Integer pages) {
	}

	Info store(UUID organizationId, UUID userId, String conversationId, String fileName, byte[] data) {
		String name = cleanName(fileName);
		String mediaType = mediaTypeOf(data);
		if (mediaType == null) {
			throw new InvalidRequestException("\"%s\" isn't a JPEG, PNG, WebP or GIF image or a PDF".formatted(name));
		}
		boolean pdf = "application/pdf".equals(mediaType);
		long maxSize = (pdf ? this.limits.maxPdfSize() : this.limits.maxImageSize()).toBytes();
		if (data.length > maxSize) {
			throw new PayloadTooLargeException("\"%s\" is larger than %d MB".formatted(name, maxSize / (1024 * 1024)));
		}
		Integer pages = pdf ? checkPdf(name, data) : null;
		if (!pdf) {
			checkImage(name, data);
		}

		Map<String, Object> existing = this.jdbcClient
			.sql("SELECT count(*) AS files, coalesce(sum(size_bytes), 0) AS bytes FROM ai_attachment "
					+ "WHERE user_id = ? AND conversation_id = ?")
			.params(userId, conversationId)
			.query()
			.singleRow();
		if (((Number) existing.get("files")).intValue() >= this.limits.maxPerConversation()) {
			throw new InvalidRequestException(
					"A conversation can have at most %d files; start a new chat for more".formatted(this.limits.maxPerConversation()));
		}
		if (((Number) existing.get("bytes")).longValue() + data.length > this.limits.maxTotalSize().toBytes()) {
			throw new PayloadTooLargeException("This conversation's files would be larger than %d MB in total"
				.formatted(this.limits.maxTotalSize().toMegabytes()));
		}

		UUID id = UUID.randomUUID();
		this.jdbcClient
			.sql("INSERT INTO ai_attachment (id, organization_id, user_id, conversation_id, file_name, media_type, "
					+ "size_bytes, data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
			.params(id, organizationId, userId, conversationId, name, mediaType, data.length, data,
					Timestamp.from(Instant.now()))
			.update();
		return new Info(id, name, mediaType, data.length, pages);
	}

	/**
	 * The files with these ids, in this order: all of them must be the user's and belong to
	 * the conversation.
	 * @throws InvalidRequestException when one is missing (deleted, expired or someone else's)
	 */
	List<Attachment> find(UUID userId, String conversationId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, Attachment> found = this.jdbcClient
			.sql("SELECT id, file_name, media_type, data FROM ai_attachment "
					+ "WHERE user_id = ? AND conversation_id = ? AND id = ANY(?)")
			.params(userId, conversationId, ids.toArray(UUID[]::new))
			.query((rs, row) -> Map.entry(rs.getObject("id", UUID.class),
					new Attachment(rs.getString("file_name"), rs.getString("media_type"), rs.getBytes("data"))))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		List<Attachment> attachments = new ArrayList<>();
		for (UUID id : ids.stream().distinct().toList()) {
			Attachment attachment = found.get(id);
			if (attachment == null) {
				throw new InvalidRequestException(
						"An attached file is no longer available; start a new chat and attach it again");
			}
			attachments.add(attachment);
		}
		return attachments;
	}

	void delete(UUID userId, UUID id) {
		int deleted = this.jdbcClient.sql("DELETE FROM ai_attachment WHERE id = ? AND user_id = ?")
			.params(id, userId)
			.update();
		if (deleted == 0) {
			throw new NotFoundException("No such attachment");
		}
	}

	/** Deletes files older than the retention period; returns how many. */
	int deleteExpired(Instant now) {
		return this.jdbcClient.sql("DELETE FROM ai_attachment WHERE created_at < ?")
			.param(Timestamp.from(now.minus(this.limits.retention())))
			.update();
	}

	/** The media type from the file's first bytes; null when it's none of the accepted kinds. */
	static String mediaTypeOf(byte[] data) {
		if (startsWith(data, 0, 0xFF, 0xD8, 0xFF)) {
			return "image/jpeg";
		}
		if (startsWith(data, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return "image/png";
		}
		if (startsWith(data, 0, 'G', 'I', 'F', '8')) {
			return "image/gif";
		}
		if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
			return "image/webp";
		}
		if (startsWith(data, 0, '%', 'P', 'D', 'F', '-')) {
			return "application/pdf";
		}
		return null;
	}

	private static boolean startsWith(byte[] data, int offset, int... expected) {
		if (data.length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((data[offset + i] & 0xFF) != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private int checkPdf(String name, byte[] data) {
		try (PDDocument document = Loader.loadPDF(data)) {
			int pages = document.getNumberOfPages();
			if (pages == 0) {
				throw new InvalidRequestException("\"%s\" has no pages".formatted(name));
			}
			if (pages > this.limits.maxPdfPages()) {
				throw new InvalidRequestException("\"%s\" has %d pages; attach at most %d (only the pages with the plan)"
					.formatted(name, pages, this.limits.maxPdfPages()));
			}
			return pages;
		}
		catch (InvalidPasswordException ex) {
			throw new InvalidRequestException("\"%s\" is password-protected; attach a copy without a password".formatted(name));
		}
		catch (IOException ex) {
			throw new InvalidRequestException("\"%s\" isn't a readable PDF".formatted(name));
		}
	}

	/** Checks the image's size where Java can read it without decoding it (JPEG, PNG, GIF; not WebP). */
	private static void checkImage(String name, byte[] data) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				if (reader.getWidth(0) > MAX_IMAGE_SIDE || reader.getHeight(0) > MAX_IMAGE_SIDE) {
					throw new InvalidRequestException(
							"\"%s\" is larger than %d pixels on a side".formatted(name, MAX_IMAGE_SIDE));
				}
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException ex) {
			throw new InvalidRequestException("\"%s\" isn't a readable image".formatted(name));
		}
	}

	/** The file name without any folder, control characters or excess length. */
	static String cleanName(String fileName) {
		String name = (fileName == null) ? "" : fileName;
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		name = name.codePoints()
			.filter((c) -> !Character.isISOControl(c))
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString()
			.strip();
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH);
		}
		return name.isEmpty() ? "file" : name;
	}

}
