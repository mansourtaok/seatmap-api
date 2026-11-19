package com.seatmap.api.ai;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The venue designer's AI assistant. Needs a login with the EDITOR role (and the assistant
 * switched on with {@code seatmap.ai.enabled}). Answers 200 with the assistant's proposal
 * ({@code status}, {@code message}, {@code operations}, {@code warnings}: the designer's
 * contract, ai-contract.ts), 413 when the venue is too large,
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
	 */
	record ChatRequest(@NotBlank @Size(max = 64) String conversationId, UUID layoutId,
			@NotBlank @Size(max = 2000) String prompt, @NotNull JsonNode venue,
			@NotNull @Size(max = 24) List<@Valid HistoryTurn> history) {
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
				history);
	}

}
