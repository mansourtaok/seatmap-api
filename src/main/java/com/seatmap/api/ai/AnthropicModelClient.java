package com.seatmap.api.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaBase64PdfSource;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.seatmap.api.web.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * {@link AiModelClient} on Claude, through the official Anthropic Java SDK.
 * <ul>
 * <li>The answer is a series of tool calls, one tool per kind of operation, each with its own
 * schema; they are collected, not run. (One output schema covering every operation exceeds
 * structured outputs' limits on optional and union-typed fields.) The designer validates every
 * value.</li>
 * <li>The fixed instructions are marked for prompt caching: repeated calls pay a tenth for them.</li>
 * <li>The call streams and is collected into the final message, so a long answer never hits an
 * HTTP timeout.</li>
 * <li>Attached files open the conversation, in a message of their own before the history, with
 * a cache mark after the last: they stay in view when old turns are dropped, and every later
 * request of the conversation reads them from the cache.</li>
 * <li>Refusals fall back server-side to another model (beta {@code server-side-fallback}), so
 * an over-cautious decline on a harmless layout request is retried rather than shown.</li>
 * </ul>
 */
@Component
class AnthropicModelClient implements AiModelClient, DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicModelClient.class);

	private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private static final Duration PROVIDER_RETRY_AFTER = Duration.ofSeconds(30);

	/** Rounds of "Recorded." before giving up on an answer that never calls the finish tool. */
	private static final int MAX_ROUNDS = 4;

	private final AiProperties properties;

	private final JsonMapper jsonMapper;

	/** Created on first use, so the app starts (and tests run) without an API key. */
	private volatile AnthropicClient client;

	AnthropicModelClient(AiProperties properties, JsonMapper jsonMapper) {
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public ModelReply complete(String system, List<Attachment> attachments, List<Turn> history, String userMessage,
			String toolsJson, String finishTool) {
		MessageCreateParams.Builder params = MessageCreateParams.builder()
			.model(this.properties.model())
			.maxTokens(this.properties.maxTokens())
			// Tools and system prompt never change: cached, they cost a tenth on every later call.
			.systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
				.text(system)
				.cacheControl(BetaCacheControlEphemeral.builder().build())
				.build()))
			// And the conversation so far, for the follow-up rounds below.
			.cacheControl(BetaCacheControlEphemeral.builder().build())
			.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(this.properties.effort())).build())
			.addBeta(FALLBACK_BETA)
			.fallbacksDefault();
		tools(toolsJson).forEach(params::addTool);
		if (!attachments.isEmpty()) {
			params.addUserMessageOfContentBlockParams(referenceFiles(attachments));
			params.addAssistantMessage("I have the reference files and will use them for this venue.");
		}
		for (Turn turn : history) {
			if (turn.role() == Turn.Role.USER) {
				params.addUserMessage(turn.text());
			}
			else {
				params.addAssistantMessage(turn.text());
			}
		}
		params.addUserMessage(userMessage);

		List<ToolCall> calls = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		TokenUsage usage = TokenUsage.NONE;
		String model = this.properties.model();
		for (int round = 1; round <= MAX_ROUNDS; round++) {
			BetaMessage message = send(params.build());
			usage = usage.plus(usage(message.usage()));
			model = message.model().asString();
			List<BetaToolUseBlock> toolUses = new ArrayList<>();
			for (BetaContentBlock block : message.content()) {
				block.text().ifPresent((t) -> text.append(t.text()));
				block.toolUse().ifPresent(toolUses::add);
			}
			toolUses.forEach((use) -> calls.add(new ToolCall(use.name(), this.jsonMapper.valueToTree(use._input().convert(Map.class)))));

			BetaStopReason stop = message.stopReason().orElse(BetaStopReason.END_TURN);
			if (stop.equals(BetaStopReason.REFUSAL)) {
				message.stopDetails().ifPresent((details) -> logger.info("Claude declined an assistant request: {}", details));
				return new ModelReply(text.toString(), calls, ModelReply.Outcome.REFUSED, model, usage);
			}
			if (stop.equals(BetaStopReason.MAX_TOKENS)) {
				return new ModelReply(text.toString(), calls, ModelReply.Outcome.TRUNCATED, model, usage);
			}
			boolean finished = toolUses.stream().anyMatch((use) -> use.name().equals(finishTool));
			if (finished || !stop.equals(BetaStopReason.TOOL_USE)) {
				return new ModelReply(text.toString(), calls, ModelReply.Outcome.COMPLETE, model, usage);
			}
			// It stopped to wait for results: confirm the calls were recorded, and let it go on.
			params.addMessage(message.toParam());
			params.addUserMessageOfContentBlockParams(toolUses.stream()
				.map((use) -> BetaContentBlockParam.ofToolResult(
						BetaToolResultBlockParam.builder().toolUseId(use.id()).content("Recorded.").build()))
				.toList());
		}
		return new ModelReply(text.toString(), calls, ModelReply.Outcome.TRUNCATED, model, usage);
	}

	/**
	 * The organizer's files, each introduced by its number and name so later turns can refer to
	 * them ("the second photo"). The last carries the cache mark.
	 */
	static List<BetaContentBlockParam> referenceFiles(List<Attachment> attachments) {
		List<BetaContentBlockParam> blocks = new ArrayList<>();
		blocks.add(BetaContentBlockParam.ofText(
				"The organizer attached these reference files for the venue in this conversation:"));
		for (int i = 0; i < attachments.size(); i++) {
			Attachment attachment = attachments.get(i);
			boolean last = i == attachments.size() - 1;
			String data = Base64.getEncoder().encodeToString(attachment.data());
			blocks.add(BetaContentBlockParam.ofText("File %d: %s".formatted(i + 1, attachment.name())));
			if (attachment.isPdf()) {
				BetaRequestDocumentBlock.Builder document = BetaRequestDocumentBlock.builder()
					.source(BetaBase64PdfSource.builder().data(data).build())
					.title(attachment.name());
				if (last) {
					document.cacheControl(BetaCacheControlEphemeral.builder().build());
				}
				blocks.add(BetaContentBlockParam.ofDocument(document.build()));
			}
			else {
				BetaImageBlockParam.Builder image = BetaImageBlockParam.builder()
					.source(BetaBase64ImageSource.builder()
						.mediaType(BetaBase64ImageSource.MediaType.of(attachment.mediaType()))
						.data(data)
						.build());
				if (last) {
					image.cacheControl(BetaCacheControlEphemeral.builder().build());
				}
				blocks.add(BetaContentBlockParam.ofImage(image.build()));
			}
		}
		return blocks;
	}

	@SuppressWarnings("unchecked")
	private List<BetaTool> tools(String json) {
		List<Map<String, Object>> definitions = this.jsonMapper.readValue(json, List.class);
		return definitions.stream().map((definition) -> {
			Map<String, Object> schema = (Map<String, Object>) definition.get("input_schema");
			BetaTool.InputSchema.Properties.Builder properties = BetaTool.InputSchema.Properties.builder();
			((Map<String, Object>) schema.get("properties"))
				.forEach((name, property) -> properties.putAdditionalProperty(name, JsonValue.from(property)));
			return BetaTool.builder()
				.name((String) definition.get("name"))
				.description((String) definition.get("description"))
				.inputSchema(BetaTool.InputSchema.builder()
					.properties(properties.build())
					.required((List<String>) schema.get("required"))
					.putAdditionalProperty("additionalProperties", JsonValue.from(false))
					.build())
				.build();
		}).toList();
	}

	private BetaMessage send(MessageCreateParams params) {
		BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
		try (StreamResponse<BetaRawMessageStreamEvent> stream = client().beta().messages().createStreaming(params)) {
			stream.stream().forEach(accumulator::accumulate);
			return accumulator.message();
		}
		// Most specific first: each kind of failure means something different to the user.
		catch (RateLimitException ex) {
			throw new TooManyRequestsException("The AI service is busy; try again shortly", PROVIDER_RETRY_AFTER);
		}
		catch (UnauthorizedException | PermissionDeniedException | NoCredentialsException ex) {
			logger.error("The Anthropic API key is missing or not accepted (ANTHROPIC_API_KEY): {}", ex.getMessage());
			throw new AiUnavailableException("The AI assistant isn't configured correctly", ex);
		}
		catch (BadRequestException ex) {
			// Our request was malformed: a bug (or a schema/model mismatch), not the user's fault.
			logger.error("Claude rejected the assistant request: {}", ex.getMessage());
			throw new AiUnavailableException("The AI assistant hit an internal problem", ex);
		}
		catch (AnthropicServiceException ex) {
			logger.warn("Claude call failed ({}): {}", ex.statusCode(), ex.getMessage());
			throw new AiUnavailableException("The AI service is unavailable; try again shortly", ex);
		}
		catch (AnthropicIoException ex) {
			// With the whole cause chain: "Request failed" alone says nothing about why.
			logger.warn("Claude call failed: {}", ex.getMessage(), ex);
			throw new AiUnavailableException("The AI service didn't answer in time; try again", ex);
		}
	}

	private AnthropicClient client() {
		AnthropicClient current = this.client;
		if (current == null) {
			synchronized (this) {
				current = this.client;
				if (current == null) {
					current = AnthropicOkHttpClient.builder()
						.fromEnv()
						.timeout(this.properties.timeout())
						.maxRetries(2)
						.build();
					this.client = current;
				}
			}
		}
		return current;
	}

	private static TokenUsage usage(BetaUsage usage) {
		return new TokenUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L),
				usage.cacheCreationInputTokens().orElse(0L));
	}

	@Override
	public void destroy() {
		if (this.client != null) {
			this.client.close();
		}
	}

}
