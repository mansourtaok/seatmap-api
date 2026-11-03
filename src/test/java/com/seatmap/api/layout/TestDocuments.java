package com.seatmap.api.layout;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The designer's sample templates (converted to schema 3), plus a small stand. */
final class TestDocuments {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private TestDocuments() {
	}

	/** small-theater (8 rows, 112 seats), banquet-hall (8 tables, 80 seats) or conference-standing. */
	static ObjectNode load(String name) {
		try (InputStream in = TestDocuments.class.getResourceAsStream("/layouts/" + name + ".json")) {
			return (ObjectNode) JSON.readTree(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A stand owning two seats in block N1, row 3: the first in category cat-vip, the second blocked. */
	static ObjectNode stand() {
		ObjectNode document = load("conference-standing");
		document.put("name", "Arena");
		ArrayNode categories = document.putArray("ticketCategories");
		categories.addObject().put("id", "cat-vip").put("name", "VIP").put("color", "#f472b6").put("price", 120.5);
		ArrayNode objects = document.putArray("objects");
		ObjectNode stand = objects.addObject()
			.put("id", "stand-n")
			.put("type", "STAND")
			.put("name", "North Stand")
			.put("x", 10)
			.put("y", 2);
		stand.putArray("seatIds").add("seat-n1").add("seat-n2");
		objects.addObject()
			.put("id", "seat-n1")
			.put("type", "SEAT")
			.put("parentId", "stand-n")
			.put("name", "Seat 7")
			.put("seatNumber", "7")
			.put("block", "N1")
			.put("row", "3")
			.put("ticketCategoryId", "cat-vip")
			.put("x", 9.5)
			.put("y", 2.25);
		objects.addObject()
			.put("id", "seat-n2")
			.put("type", "SEAT")
			.put("parentId", "stand-n")
			.put("name", "Seat 8")
			.put("seatNumber", "8")
			.put("block", "N1")
			.put("row", "3")
			.put("status", "blocked")
			.put("x", 10.5)
			.put("y", 2.25);
		return document;
	}

	static ObjectNode object(ObjectNode document, String id) {
		for (JsonNode object : document.get("objects").values()) {
			if (object.get("id").asString().equals(id)) {
				return (ObjectNode) object;
			}
		}
		throw new IllegalArgumentException(id);
	}

}
