package com.seatmap.api.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Integrity checks on a designer document before it is stored. Geometry and layout rules
 * stay in the designer; this makes sure what the server keeps (and later sells from) is
 * consistent: known types, unique ids, seats and their owners pointing at each other, and
 * categories and assets that exist.
 */
final class LayoutDocumentValidator {

	/**
	 * Newest document format this server understands. Version 4 only added optional heights
	 * for the 3D view and version 5 optional per-block stand sizes, so version 3 documents are
	 * still accepted as they are.
	 */
	static final int SCHEMA_VERSION = 5;

	static final int OLDEST_SCHEMA_VERSION = 3;

	static final Set<String> OBJECT_TYPES = Set.of("STAGE", "SHAPE", "TEXT", "TABLE_ROUND", "TABLE_RECT", "SEAT",
			"SEAT_ROW", "SECTION", "STANDING_AREA", "PASSAGE", "WALL", "DOOR", "BOOTH", "PLAYING_SURFACE", "STAND");

	/** Types that own seats through {@code seatIds}. */
	static final Set<String> SEAT_OWNER_TYPES = Set.of("TABLE_ROUND", "TABLE_RECT", "SEAT_ROW", "STAND");

	private static final Set<String> DESIGN_SEAT_STATUSES = Set.of("held", "blocked");

	private static final int MAX_REPORTED = 50;

	private LayoutDocumentValidator() {
	}

	/** Returns the problems found, empty if the document is valid. */
	static List<String> validate(JsonNode document) {
		Problems problems = new Problems();
		if (document == null || !document.isObject()) {
			problems.add("The document must be a JSON object");
			return problems.list();
		}
		JsonNode schemaVersion = document.get("schemaVersion");
		if (schemaVersion == null || !schemaVersion.isIntegralNumber()) {
			problems.add("schemaVersion is missing");
			return problems.list();
		}
		if (schemaVersion.asInt() < OLDEST_SCHEMA_VERSION || schemaVersion.asInt() > SCHEMA_VERSION) {
			problems.add(("Schema version %d is not supported; this server accepts versions %d to %d. "
					+ "Open and save the layout in the current designer to upgrade it.")
				.formatted(schemaVersion.asInt(), OLDEST_SCHEMA_VERSION, SCHEMA_VERSION));
			return problems.list();
		}
		if (!isNonBlankString(document.get("name"))) {
			problems.add("name is missing");
		}
		for (String field : List.of("venue", "grid", "assets", "metadata")) {
			if (!isObject(document.get(field))) {
				problems.add(field + " must be an object");
			}
		}
		JsonNode objects = document.get("objects");
		JsonNode categories = document.get("ticketCategories");
		if (objects == null || !objects.isArray()) {
			problems.add("objects must be an array");
		}
		if (categories == null || !categories.isArray()) {
			problems.add("ticketCategories must be an array");
		}
		if (problems.any()) {
			return problems.list();
		}

		Set<String> categoryIds = checkCategories(categories, problems);
		Map<String, JsonNode> objectsById = indexObjects(objects, problems);
		for (JsonNode object : objectsById.values()) {
			checkObject(object, objectsById, categoryIds, problems);
		}
		checkBackground(document, problems);
		checkStageImages(document, objectsById, problems);
		return problems.list();
	}

	private static Set<String> checkCategories(JsonNode categories, Problems problems) {
		Set<String> ids = new HashSet<>();
		for (JsonNode category : categories.values()) {
			JsonNode id = category.get("id");
			if (!isNonBlankString(id)) {
				problems.add("A ticket category has no id");
				continue;
			}
			String where = "Ticket category '" + id.asString() + "'";
			if (!ids.add(id.asString())) {
				problems.add(where + " is defined twice");
			}
			if (!isNonBlankString(category.get("name"))) {
				problems.add(where + " has no name");
			}
			if (!isString(category.get("color"))) {
				problems.add(where + " has no color");
			}
			JsonNode price = category.get("price");
			if (price == null || !price.isNumber() || price.asDouble() < 0) {
				problems.add(where + " needs a price of 0 or more");
			}
		}
		return ids;
	}

	private static Map<String, JsonNode> indexObjects(JsonNode objects, Problems problems) {
		Map<String, JsonNode> byId = new HashMap<>();
		int index = 0;
		for (JsonNode object : objects.values()) {
			JsonNode id = object.get("id");
			if (!object.isObject() || !isNonBlankString(id)) {
				problems.add("objects[%d] has no id".formatted(index));
			}
			else if (byId.putIfAbsent(id.asString(), object) != null) {
				problems.add("Object id '" + id.asString() + "' is used more than once");
			}
			index++;
		}
		return byId;
	}

	private static void checkObject(JsonNode object, Map<String, JsonNode> objectsById, Set<String> categoryIds,
			Problems problems) {
		String where = "Object '" + object.get("id").asString() + "'";
		String type = isString(object.get("type")) ? object.get("type").asString() : null;
		if (type == null || !OBJECT_TYPES.contains(type)) {
			problems.add(where + " has an unknown type: " + object.get("type"));
			return;
		}
		if (!isNumber(object.get("x")) || !isNumber(object.get("y"))) {
			problems.add(where + " needs numeric x and y");
		}
		JsonNode categoryId = object.get("ticketCategoryId");
		if (categoryId != null && !categoryId.isNull()
				&& (!categoryId.isString() || !categoryIds.contains(categoryId.asString()))) {
			problems.add(where + " uses ticket category " + categoryId + ", which doesn't exist");
		}

		if (type.equals("SEAT")) {
			checkSeat(object, where, objectsById, problems);
		}
		else if (SEAT_OWNER_TYPES.contains(type)) {
			checkSeatOwner(object, where, objectsById, problems);
		}
		else if (type.equals("STANDING_AREA") || type.equals("SECTION")) {
			JsonNode capacity = object.get("capacity");
			if (capacity == null || !capacity.isIntegralNumber() || capacity.asInt() < 0) {
				problems.add(where + " needs a whole-number capacity of 0 or more");
			}
		}
	}

	private static void checkSeat(JsonNode seat, String where, Map<String, JsonNode> objectsById, Problems problems) {
		if (!isString(seat.get("seatNumber"))) {
			problems.add(where + " has no seatNumber");
		}
		JsonNode status = seat.get("status");
		if (status != null && !status.isNull()
				&& (!status.isString() || !DESIGN_SEAT_STATUSES.contains(status.asString()))) {
			problems.add(where + " has status " + status + "; a layout can only save 'held' or 'blocked'");
		}
		JsonNode parentId = seat.get("parentId");
		if (parentId == null || parentId.isNull()) {
			return;
		}
		JsonNode parent = isString(parentId) ? objectsById.get(parentId.asString()) : null;
		if (parent == null || !SEAT_OWNER_TYPES.contains(parent.path("type").asString())) {
			problems.add(where + " belongs to " + parentId + ", which is not a table, row or stand in this layout");
		}
		else if (!containsString(parent.get("seatIds"), seat.get("id").asString())) {
			problems.add(where + " belongs to " + parentId + ", but isn't in its seatIds");
		}
	}

	private static void checkSeatOwner(JsonNode owner, String where, Map<String, JsonNode> objectsById,
			Problems problems) {
		JsonNode seatIds = owner.get("seatIds");
		if (seatIds == null || !seatIds.isArray()) {
			problems.add(where + " needs a seatIds array");
			return;
		}
		String ownerId = owner.get("id").asString();
		for (JsonNode seatId : seatIds.values()) {
			JsonNode seat = isString(seatId) ? objectsById.get(seatId.asString()) : null;
			if (seat == null || !"SEAT".equals(seat.path("type").asString())) {
				problems.add(where + " lists seat " + seatId + ", which is not a seat in this layout");
			}
			else if (!ownerId.equals(seat.path("parentId").asString())) {
				problems.add(where + " lists seat " + seatId + ", but that seat's parentId is " + seat.get("parentId"));
			}
		}
	}

	private static void checkBackground(JsonNode document, Problems problems) {
		JsonNode background = document.get("background");
		if (background == null || background.isNull()) {
			return;
		}
		JsonNode assetId = background.get("assetId");
		if (!isString(assetId) || !document.get("assets").has(assetId.asString())) {
			problems.add("The floor plan uses asset " + assetId + ", which isn't listed in assets");
		}
	}

	/** A stage's picture (shown in 3D) must be one of the layout's assets, like the floor plan. */
	private static void checkStageImages(JsonNode document, Map<String, JsonNode> objectsById, Problems problems) {
		for (JsonNode object : objectsById.values()) {
			JsonNode image = object.get("image");
			if (!"STAGE".equals(object.path("type").asString()) || image == null || image.isNull()) {
				continue;
			}
			JsonNode assetId = image.get("assetId");
			if (!isString(assetId) || !document.get("assets").has(assetId.asString())) {
				problems.add("Stage " + object.get("id") + " shows asset " + assetId + ", which isn't listed in assets");
			}
		}
	}

	private static boolean containsString(JsonNode array, String value) {
		if (array == null || !array.isArray()) {
			return false;
		}
		for (JsonNode element : array.values()) {
			if (element.isString() && element.asString().equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isString(JsonNode node) {
		return node != null && node.isString();
	}

	private static boolean isNonBlankString(JsonNode node) {
		return isString(node) && !node.asString().isBlank();
	}

	private static boolean isNumber(JsonNode node) {
		return node != null && node.isNumber();
	}

	private static boolean isObject(JsonNode node) {
		return node != null && node.isObject();
	}

	/** Keeps the first problems found, so a broken stadium doesn't produce 100,000 lines. */
	private static final class Problems {

		private final List<String> reported = new ArrayList<>();

		private int total;

		void add(String problem) {
			if (this.total++ < MAX_REPORTED) {
				this.reported.add(problem);
			}
		}

		boolean any() {
			return this.total > 0;
		}

		List<String> list() {
			List<String> result = new ArrayList<>(this.reported);
			if (this.total > MAX_REPORTED) {
				result.add("... and %d more problems".formatted(this.total - MAX_REPORTED));
			}
			return List.copyOf(result);
		}

	}

}
