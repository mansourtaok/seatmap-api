package com.seatmap.api.layout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Works out what a valid document sells, with the same rules as the designer's ticket
 * stats: every seat, standing areas by capacity, and sections marked {@code soldByCapacity}.
 * Tables, rows, stands and other sections only pass their category on to what's in them,
 * so a seat's own {@code ticketCategoryId} is the one that counts.
 */
final class InventoryExtractor {

	private InventoryExtractor() {
	}

	static Inventory extract(JsonNode document) {
		Map<String, JsonNode> objectsById = new HashMap<>();
		for (JsonNode object : document.get("objects").values()) {
			objectsById.put(object.get("id").asString(), object);
		}

		List<InventoryUnit> units = new ArrayList<>();
		for (JsonNode object : document.get("objects").values()) {
			switch (object.get("type").asString()) {
				case "SEAT" -> units.add(seat(object, objectsById));
				case "STANDING_AREA" -> units.add(area(object, InventoryUnit.Kind.STANDING_AREA));
				case "SECTION" -> {
					if (object.path("soldByCapacity").asBoolean()) {
						units.add(area(object, InventoryUnit.Kind.SECTION_GA));
					}
				}
				default -> {
				}
			}
		}

		List<TicketCategory> categories = new ArrayList<>();
		for (JsonNode category : document.get("ticketCategories").values()) {
			categories.add(new TicketCategory(category.get("id").asString(), category.get("name").asString(),
					category.get("color").asString(),
					BigDecimal.valueOf(category.get("price").asDouble()).setScale(2, RoundingMode.HALF_UP)));
		}
		return new Inventory(categories, units);
	}

	private static InventoryUnit seat(JsonNode seat, Map<String, JsonNode> objectsById) {
		String parentId = text(seat, "parentId");
		JsonNode parent = parentId != null ? objectsById.get(parentId) : null;
		boolean inRow = parent != null && "SEAT_ROW".equals(text(parent, "type"));
		String parentName = parent != null ? text(parent, "name") : null;
		String block = text(seat, "block");
		String row = text(seat, "row");
		if (row == null && inRow) {
			row = text(parent, "rowLabel");
		}
		String seatNumber = text(seat, "seatNumber");

		List<String> label = new ArrayList<>();
		if (parentName != null && !parentName.isBlank()) {
			label.add(parentName);
		}
		if (block != null) {
			label.add("Block " + block);
		}
		// A seat row's name already says which row it is ("Row A").
		if (row != null && !inRow) {
			label.add("Row " + row);
		}
		label.add("Seat " + seatNumber);

		return new InventoryUnit(text(seat, "id"), InventoryUnit.Kind.SEAT, 1, parentId, parentName, block, row,
				seatNumber, String.join(" · ", label), text(seat, "ticketCategoryId"), text(seat, "status"),
				seat.get("x").asDouble(), seat.get("y").asDouble());
	}

	private static InventoryUnit area(JsonNode area, InventoryUnit.Kind kind) {
		return new InventoryUnit(text(area, "id"), kind, area.get("capacity").asInt(), null, null, null, null, null,
				text(area, "name"), text(area, "ticketCategoryId"), null, area.get("x").asDouble(),
				area.get("y").asDouble());
	}

	/** The field's string value, or null when it is absent, null or not a string. */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.asString() : null;
	}

}
