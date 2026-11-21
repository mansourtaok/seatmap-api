package com.seatmap.api.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import com.seatmap.api.web.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The venue designer's AI assistant. Needs a login with the EDITOR role (and the assistant
 * switched on with {@code seatmap.ai.enabled}). Answers 200 with the assistant's proposal
 * ({@code status}, {@code message}, {@code operations}, {@code warnings}: the designer's
 * contract, ai-contract.ts), 413 when the venue (or an attached file) is too large,
 * 402 when the organization has used this month's AI allowance, 429 when rate limited and 503
 * when the AI service fails.
 */
@RestController
@RequestMapping("/api/ai/venue-assistant")
class VenueAssistantController {

	private final VenueAssistantService service;

	VenueAssistantController(VenueAssistantService service) {
		this.service = service;
	}

	/**
	 * @param conversationId groups one chat's requests in the usage records
	 * @param layoutId the saved layout being edited, if it has been saved
	 * @param prompt what the organizer asked
	 * @param venue the designer's summary of the open venue
	 * @param history earlier turns, oldest first
	 * @param attachmentIds the conversation's attached files so far (see {@code /attachments}), in the
	 * order they were attached; optional
	 */
	record ChatRequest(@NotBlank @Size(max = 64) String conversationId, UUID layoutId,
			@NotBlank @Size(max = 2000) String prompt, @NotNull JsonNode venue,
			@NotNull @Size(max = 24) List<@Valid HistoryTurn> history,
			@Size(max = 20) List<@NotNull UUID> attachmentIds) {

		List<UUID> attachmentIdsOrNone() {
			return (this.attachmentIds != null) ? this.attachmentIds : List.of();
		}

	}

	record HistoryTurn(@NotNull @Pattern(regexp = "user|assistant") String role,
			@NotNull @Size(max = 20000) String text) {
	}

	/** How much of this month's AI allowance is used: {@code {"percent": 35, "resetsAt": "…"}}. */
	@GetMapping("/usage")
	VenueAssistantService.Usage usage(@RequestParam(required = false) UUID layoutId) {
		return this.service.usage(layoutId);
	}

	@PostMapping("/chat")
	JsonNode chat(@Valid @RequestBody ChatRequest request) {
		List<VenueAssistantService.ChatTurn> history = request.history()
			.stream()
			.map((turn) -> new VenueAssistantService.ChatTurn(turn.role(), turn.text()))
			.toList();
		return this.service.chat(request.conversationId(), request.layoutId(), request.prompt(), request.venue(),
				history, request.attachmentIdsOrNone());
	}

	/**
	 * Attaches a photo (JPEG, PNG, WebP, GIF) or a PDF to a conversation; multipart, with the
	 * file in {@code file}. 201 with {@code id}, {@code name}, {@code mediaType}, {@code size} and,
	 * for a PDF, {@code pages}; 400 for an unsupported, unreadable or too-long file or a full
	 * conversation, 413 when it's too large. Files are kept for a day.
	 */
	@PostMapping("/attachments")
	@ResponseStatus(HttpStatus.CREATED)
	AiAttachments.Info attach(@RequestParam MultipartFile file, @RequestParam String conversationId,
			@RequestParam(required = false) UUID layoutId) {
		if (conversationId.isBlank() || conversationId.length() > 64) {
			throw new InvalidRequestException("conversationId must be 1 to 64 characters");
		}
		try {
			return this.service.attach(conversationId, layoutId, file.getOriginalFilename(), file.getBytes());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Removes an attached file the organizer took back before sending: 204. */
	@DeleteMapping("/attachments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void detach(@PathVariable UUID id) {
		this.service.detach(id);
	}

}
