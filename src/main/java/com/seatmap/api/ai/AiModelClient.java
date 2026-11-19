package com.seatmap.api.ai;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * One request to the language model. An interface so tests (and a future second provider)
 * can stand in for Claude without network calls or cost.
 */
interface AiModelClient {

	/**
	 * Asks the model to answer by calling tools. The tools are never executed here: their calls,
	 * in order, are the answer. If the model stops before calling {@code finishTool}, it is told
	 * its calls were recorded and continues, for a few rounds at most.
	 * @param system the fixed instructions
	 * @param history earlier turns, oldest first
	 * @param userMessage this turn's message
	 * @param toolsJson the tool definitions, as a JSON array of {@code name}, {@code description}, {@code input_schema}
	 * @param finishTool the tool that ends an answer
	 * @throws AiUnavailableException when the model can't be reached or fails
	 * @throws com.seatmap.api.web.TooManyRequestsException when the provider is rate limiting us
	 */
	ModelReply complete(String system, List<Turn> history, String userMessage, String toolsJson, String finishTool);

	record Turn(Role role, String text) {

		enum Role {

			USER, ASSISTANT

		}

	}

	record ToolCall(String name, JsonNode input) {
	}

	/**
	 * @param text any text the model wrote besides its tool calls
	 * @param calls its tool calls, in order, across all rounds
	 * @param outcome how the answer ended
	 * @param model the model that actually answered (a refusal fallback may switch it)
	 * @param usage tokens across all rounds
	 */
	record ModelReply(String text, List<ToolCall> calls, Outcome outcome, String model, TokenUsage usage) {

		enum Outcome {

			COMPLETE,
			/** Claude declined for safety reasons. */
			REFUSED,
			/** Ran out of tokens, or of rounds, before finishing. */
			TRUNCATED

		}

	}

	record TokenUsage(long input, long output, long cacheRead, long cacheWrite) {

		static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

		TokenUsage plus(TokenUsage other) {
			return new TokenUsage(this.input + other.input, this.output + other.output,
					this.cacheRead + other.cacheRead, this.cacheWrite + other.cacheWrite);
		}

	}

}
