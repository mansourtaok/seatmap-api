package com.seatmap.api.web;

/** Revisions as HTTP entity tags: revision 3 is sent as ETag {@code "3"} and comes back in If-Match. */
public final class Revisions {

	private Revisions() {
	}

	public static String eTag(int revision) {
		return "\"" + revision + "\"";
	}

	/** Reads a revision from an If-Match value: {@code "3"}, {@code W/"3"} or a bare 3. */
	public static int fromIfMatch(String ifMatch) {
		String value = ifMatch.strip();
		if (value.startsWith("W/")) {
			value = value.substring(2);
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex) {
			throw new InvalidRequestException("If-Match must be an ETag from this API, such as \"3\"");
		}
	}

}
