package com.seatmap.api.ticket;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import org.springframework.stereotype.Component;

/**
 * Prints an order's tickets: one A4 page each, readable on a phone or printed, with the QR
 * code the door scans.
 * <p>
 * Uses the PDF standard Helvetica font, which covers Western European text; other characters
 * (Arabic, say) print as "?" until a Unicode font is embedded.
 */
@Component
class TicketPdf {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm",
			Locale.ENGLISH);

	private static final Color INK = new Color(15, 23, 42);

	private static final Color MUTED = new Color(100, 116, 139);

	private final TicketCodes codes;

	TicketPdf(TicketCodes codes) {
		this.codes = codes;
	}

	byte[] render(TicketRepository.Order order, List<TicketRepository.Ticket> tickets) {
		try (PDDocument document = new PDDocument()) {
			PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
			PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			int number = 0;
			for (TicketRepository.Ticket ticket : tickets) {
				number++;
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					drawTicket(document, content, page.getMediaBox(), bold, regular, order, ticket, number,
							tickets.size());
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void drawTicket(PDDocument document, PDPageContentStream content, PDRectangle box, PDFont bold,
			PDFont regular, TicketRepository.Order order, TicketRepository.Ticket ticket, int number, int count)
			throws IOException {
		float width = box.getWidth();
		float top = box.getHeight();
		float left = 50;

		content.setNonStrokingColor(INK);
		content.addRect(0, top - 120, width, 120);
		content.fill();
		text(content, bold, 24, left, top - 60, Color.WHITE, order.eventName());
		text(content, regular, 13, left, top - 88, Color.WHITE,
				WHEN.format(order.startsAt().atZone(order.timeZone())) + " (" + order.timeZone().getId() + ")");

		float y = top - 175;
		text(content, regular, 11, left, y, MUTED, "Seat");
		String seat = ticket.label() + ((ticket.places() > 1) ? " · Place %d of %d".formatted(ticket.place(),
				ticket.places()) : "");
		text(content, bold, 20, left, y - 26, INK, seat);

		y -= 80;
		row(content, bold, regular, left, y, "Category", ticket.categoryName());
		row(content, bold, regular, left, y - 40, "Price", ticket.price().toPlainString() + " " + order.currency());
		row(content, bold, regular, left, y - 80, "Venue", order.venueName());
		row(content, bold, regular, left, y - 120, "Name",
				(order.buyerName() != null) ? order.buyerName() : "Box office sale");
		row(content, bold, regular, left, y - 160, "Order", order.reservationId().toString());

		float qrSize = 230;
		float qrTop = y - 210;
		content.drawImage(qrImage(document, this.codes.codeFor(ticket.id())), (width - qrSize) / 2,
				qrTop - qrSize, qrSize, qrSize);
		centered(content, regular, 10, width, qrTop - qrSize - 20, MUTED,
				"Show this code at the entrance. It lets one person in, once.");

		content.setStrokingColor(MUTED);
		content.moveTo(left, 70);
		content.lineTo(width - left, 70);
		content.stroke();
		text(content, regular, 9, left, 52, MUTED,
				"Ticket %d of %d · %s".formatted(number, count, order.organizationName()));
	}

	private static void row(PDPageContentStream content, PDFont bold, PDFont regular, float x, float y, String name,
			String value) throws IOException {
		text(content, regular, 11, x, y, MUTED, name);
		text(content, bold, 14, x + 90, y, INK, value);
	}

	private static void centered(PDPageContentStream content, PDFont font, float size, float pageWidth, float y,
			Color color, String value) throws IOException {
		String printable = printable(font, value);
		float width = font.getStringWidth(printable) / 1000 * size;
		text(content, font, size, (pageWidth - width) / 2, y, color, printable);
	}

	private static void text(PDPageContentStream content, PDFont font, float size, float x, float y, Color color,
			String value) throws IOException {
		content.beginText();
		content.setFont(font, size);
		content.setNonStrokingColor(color);
		content.newLineAtOffset(x, y);
		content.showText(printable(font, (value != null) ? value : ""));
		content.endText();
	}

	/** The text with characters the font can't draw replaced by "?". */
	private static String printable(PDFont font, String value) {
		StringBuilder printable = new StringBuilder(value.length());
		value.codePoints().forEach((codePoint) -> {
			String character = Character.toString(codePoint);
			try {
				font.encode(character);
				printable.append(character);
			}
			catch (IllegalArgumentException | IOException ex) {
				printable.append('?');
			}
		});
		return printable.toString();
	}

	private static PDImageXObject qrImage(PDDocument document, String code) throws IOException {
		try {
			BitMatrix matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0,
					Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
			BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
					BufferedImage.TYPE_BYTE_BINARY);
			for (int x = 0; x < matrix.getWidth(); x++) {
				for (int y = 0; y < matrix.getHeight(); y++) {
					image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
				}
			}
			PDImageXObject qr = LosslessFactory.createFromImage(document, image);
			// Keep the modules sharp when the small image is scaled up.
			qr.setInterpolate(false);
			return qr;
		}
		catch (WriterException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
