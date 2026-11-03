package com.seatmap.api.ticket;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class TicketCodesTests {

	private final TicketCodes codes = new TicketCodes("a-test-key-that-is-long-enough-0123456789");

	@Test
	void readsBackTheTicketItSigned() {
		UUID ticket = UUID.randomUUID();
		String code = this.codes.codeFor(ticket);
		assertThat(code).startsWith("SM1.").hasSizeLessThan(60);
		assertThat(this.codes.verify(code)).contains(ticket);
	}

	@Test
	void refusesAlteredCodes() {
		String code = this.codes.codeFor(UUID.randomUUID());
		String otherTicket = this.codes.codeFor(UUID.randomUUID());
		String swapped = code.substring(0, code.lastIndexOf('.')) + otherTicket.substring(otherTicket.lastIndexOf('.'));
		assertThat(this.codes.verify(swapped)).isEmpty();
		char last = code.charAt(code.length() - 1);
		assertThat(this.codes.verify(code.substring(0, code.length() - 1) + ((last == 'A') ? 'B' : 'A'))).isEmpty();
	}

	@Test
	void refusesCodesSignedWithAnotherKey() {
		TicketCodes forger = new TicketCodes("someone-elses-key-that-is-long-enough-012345");
		assertThat(this.codes.verify(forger.codeFor(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void refusesGarbage() {
		assertThat(this.codes.verify(null)).isEmpty();
		assertThat(this.codes.verify("")).isEmpty();
		assertThat(this.codes.verify("SM1.not-base64!.x")).isEmpty();
		assertThat(this.codes.verify("https://example.com/ticket")).isEmpty();
	}

	@Test
	void needsAProperKey() {
		assertThatIllegalStateException().isThrownBy(() -> new TicketCodes((String) null));
		assertThatIllegalStateException().isThrownBy(() -> new TicketCodes("short"));
	}

}
