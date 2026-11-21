package com.seatmap.api.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.seatmap.api.account.Access;
import com.seatmap.api.ai.AiModelClient.ModelReply;
import com.seatmap.api.ai.AiModelClient.TokenUsage;
import com.seatmap.api.ai.AiModelClient.Turn;
import com.seatmap.api.ai.AiUsageRecorder.Status;
import com.seatmap.api.web.PayloadTooLargeException;
import com.seatmap.api.web.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * The designer's AI assistant: one organizer prompt, plus a summary of the open venue, in;
 * Claude's structured proposal (status, message, operations) out. Nothing here changes a
 * layout: the designer validates the operations, previews them and applies them only when
 * the organizer confirms, and saves through the normal layout API.
 */
@Service
public class VenueAssistantService {

	private static final Logger logger = LoggerFactory.getLogger(VenueAssistantService.class);

	private final AiModelClient model;

	private final AiAccess aiAccess;

	private final AiAllowance allowance;

	private final Access access;

	private final AiUsageRecorder usage;

	private final AiProperties properties;

	private final JsonMapper jsonMapper;

	private final RateLimiter rateLimiter;

	private final AiAttachments attachments;

	private final RateLimiter uploadLimiter;

	private final String systemPrompt = resource("ai/venue-assistant-system.md");

	private final String tools = resource("ai/venue-assistant-tools.json");

	/** The tool that ends an answer, carrying its status and message; every other tool is an operation. */
	private static final String FINISH_TOOL = "finish";

	VenueAssistantService(AiModelClient model, AiAccess aiAccess, AiAllowance allowance, Access access, AiUsageRecorder usage,
			AiProperties properties, JsonMapper jsonMapper, AiAttachments attachments) {
		this.model = model;
		this.aiAccess = aiAccess;
		this.allowance = allowance;
		this.access = access;
		this.usage = usage;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.rateLimiter = new RateLimiter(properties.requestsPerMinute(), Duration.ofMinutes(1));
		this.attachments = attachments;
		this.uploadLimiter = new RateLimiter(properties.attachments().uploadsPerMinute(), Duration.ofMinutes(1));
	}

	public record ChatTurn(String role, String text) {
	}

	public record Usage(int percent, Instant resetsAt) {
	}

	/** The share of this month's AI allowance used by the organization the request counts against. */
	public Usage usage(UUID layoutId) {
		AiAllowance.Usage usage = this.allowance.usage(this.aiAccess.requireOrganization(layoutId));
		return new Usage(usage.percent(), usage.resetsAt());
	}

	/**
	 * Keeps a photo or PDF for the conversation, after checking it (see {@link AiAttachments}).
	 * Nothing is sent to Claude yet: that happens with each chat request that lists its id.
	 */
	public AiAttachments.Info attach(String conversationId, UUID layoutId, String fileName, byte[] data) {
		UUID organizationId = this.aiAccess.requireOrganization(layoutId);
		UUID userId = this.access.requireUser().id();
		this.uploadLimiter.acquire(userId.toString(), "Too many files at once; wait a moment and try again");
		return this.attachments.store(organizationId, userId, conversationId, fileName, data);
	}

	public void detach(UUID attachmentId) {
		this.attachments.delete(this.access.requireUser().id(), attachmentId);
	}

	/**
	 * @param attachmentIds the conversation's files so far, in the order they were attached
	 */
	public JsonNode chat(String conversationId, UUID layoutId, String prompt, JsonNode venue, List<ChatTurn> history,
			List<UUID> attachmentIds) {
		UUID organizationId = this.aiAccess.requireOrganization(layoutId);
		UUID userId = this.access.requireUser().id();
		this.allowance.requireAvailable(organizationId);
		List<AiModelClient.Attachment> files = this.attachments.find(userId, conversationId, attachmentIds);
		String venueJson = this.jsonMapper.writeValueAsString(venue);
		long size = venueJson.length() + prompt.length() + history.stream().mapToLong((t) -> t.text().length()).sum();
		if (size > this.properties.maxRequestSize().toBytes()) {
			throw new PayloadTooLargeException("This venue is too large to send to the assistant");
		}
		this.rateLimiter.acquire(userId.toString(), "Too many assistant requests; wait a moment and try again");

		List<Turn> turns = history.stream()
			.map((turn) -> new Turn("assistant".equals(turn.role()) ? Turn.Role.ASSISTANT : Turn.Role.USER, turn.text()))
			.toList();
		String userMessage = """
				The venue open in the designer now:
				%s

				The organizer's request:
				%s""".formatted(venueJson, prompt);

		Instant start = Instant.now();
		ModelReply reply;
		try {
			reply = this.model.complete(this.systemPrompt, files, turns, userMessage, this.tools, FINISH_TOOL);
		}
		catch (RuntimeException ex) {
			record(organizationId, userId, conversationId, layoutId, this.properties.model(), TokenUsage.NONE, start,
					Status.ERROR, ex.getMessage());
			throw ex;
		}

		switch (reply.outcome()) {
			case REFUSED -> {
				record(organizationId, userId, conversationId, layoutId, reply.model(), reply.usage(), start,
						Status.REFUSED, null);
				return cannotDo("I can't help with that request. Describe the seating layout you need and I'll build it.");
			}
			case TRUNCATED -> {
				record(organizationId, userId, conversationId, layoutId, reply.model(), reply.usage(), start,
						Status.INVALID_RESPONSE, "answer cut off at max tokens");
				throw new AiUnavailableException("The assistant's answer was too long; try asking for less at once");
			}
			case COMPLETE -> {
				JsonNode answer = answer(reply);
				log(conversationId, reply, answer);
				record(organizationId, userId, conversationId, layoutId, reply.model(), reply.usage(), start,
						statusOf(answer), null);
				return answer;
			}
			default -> throw new IllegalStateException("Unknown outcome " + reply.outcome());
		}
	}

	/**
	 * The designer's response contract, from the model's tool calls: each operation tool call
	 * becomes an operation ({@code add_stand} → {@code ADD_STAND}), in order, and the finish
	 * call gives the status, message and warnings.
	 */
	private JsonNode answer(ModelReply reply) {
		ObjectNode answer = this.jsonMapper.createObjectNode();
		ArrayNode operations = this.jsonMapper.createArrayNode();
		JsonNode finish = null;
		for (AiModelClient.ToolCall call : reply.calls()) {
			if (FINISH_TOOL.equals(call.name())) {
				finish = call.input();
				continue;
			}
			ObjectNode operation = this.jsonMapper.createObjectNode();
			operation.put("op", call.name().toUpperCase(Locale.ROOT));
			if (call.input().isObject()) {
				operation.setAll((ObjectNode) call.input());
			}
			operations.add(operation);
		}
		String text = reply.text().strip();
		if (finish != null) {
			answer.put("status", finish.path("status").asString("SUCCESS"));
			answer.put("message", finish.path("message").asString(text));
			copy(finish, answer, "question");
			copy(finish, answer, "suggestedAnswers");
		}
		else {
			// Answered in plain text (or didn't call finish): show what it wrote.
			answer.put("status", "SUCCESS");
			answer.put("message", text.isEmpty() ? "Done." : text);
		}
		answer.set("operations", operations);
		answer.set("warnings", (finish != null && finish.path("warnings").isArray()) ? finish.get("warnings")
				: this.jsonMapper.createArrayNode());
		return answer;
	}

	/**
	 * One line per answer (status, how many of each operation, tokens), and the whole answer at
	 * DEBUG, so an answer the designer can't use can be looked at afterwards.
	 */
	private void log(String conversationId, ModelReply reply, JsonNode answer) {
		java.util.Map<String, Long> counts = new java.util.TreeMap<>();
		answer.path("operations").forEach((op) -> counts.merge(op.path("op").asString(), 1L, Long::sum));
		logger.info("Assistant answer, conversation {}: {} with {} operations {}, {} output tokens", conversationId,
				answer.path("status").asString(), answer.path("operations").size(), counts, reply.usage().output());
		if (logger.isDebugEnabled()) {
			logger.debug("Assistant answer, conversation {}: {}", conversationId, answer);
		}
	}

	private static void copy(JsonNode from, ObjectNode to, String field) {
		JsonNode value = from.get(field);
		if (value != null && !value.isNull()) {
			to.set(field, value);
		}
	}

	private static Status statusOf(JsonNode answer) {
		return switch (answer.path("status").asString()) {
			case "NEEDS_CLARIFICATION" -> Status.NEEDS_CLARIFICATION;
			case "CANNOT_DO" -> Status.CANNOT_DO;
			default -> Status.SUCCESS;
		};
	}

	private JsonNode cannotDo(String message) {
		ObjectNode node = this.jsonMapper.createObjectNode();
		node.put("status", "CANNOT_DO");
		node.put("message", message);
		node.putArray("operations");
		node.putArray("warnings");
		return node;
	}

	private void record(UUID organizationId, UUID userId, String conversationId, UUID layoutId, String model,
			TokenUsage tokens, Instant start, Status status, String error) {
		Instant now = Instant.now();
		try {
			this.usage.record(new AiUsageRecorder.Call(organizationId, userId, conversationId, layoutId, model, tokens,
					Duration.between(start, now).toMillis(), status, error, now));
		}
		catch (RuntimeException ex) {
			// Losing a usage row must not lose the organizer's answer.
			logger.error("Couldn't record AI usage", ex);
		}
	}

	private static String resource(String path) {
		try {
			return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Missing " + path, ex);
		}
	}

}
