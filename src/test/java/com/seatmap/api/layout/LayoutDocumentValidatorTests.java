package com.seatmap.api.layout;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutDocumentValidatorTests {

	@ParameterizedTest
	@ValueSource(strings = { "small-theater", "banquet-hall", "conference-standing" })
	void acceptsTheDesignerSamples(String name) {
		assertThat(LayoutDocumentValidator.validate(TestDocuments.load(name))).isEmpty();
	}

	@Test
	void acceptsAStand() {
		assertThat(LayoutDocumentValidator.validate(TestDocuments.stand())).isEmpty();
	}

	@Test
	void rejectsOtherSchemaVersions() {
		ObjectNode document = TestDocuments.load("small-theater").put("schemaVersion", 2);
		assertThat(LayoutDocumentValidator.validate(document)).singleElement()
			.asString()
			.contains("Schema version 2 is not supported");
	}

	@Test
	void rejectsAMissingNameAndObjects() {
		ObjectNode document = TestDocuments.load("small-theater");
		document.remove("name");
		document.remove("objects");
		assertThat(LayoutDocumentValidator.validate(document)).containsExactly("name is missing",
				"objects must be an array");
	}

	@Test
	void rejectsDuplicateIdsAndUnknownTypes() {
		ObjectNode document = TestDocuments.load("conference-standing");
		TestDocuments.object(document, "section-66").put("id", "standing-65");
		TestDocuments.object(document, "stage-64").put("type", "SPACESHIP");
		assertThat(LayoutDocumentValidator.validate(document)).containsExactlyInAnyOrder(
				"Object id 'standing-65' is used more than once",
				"Object 'stage-64' has an unknown type: \"SPACESHIP\"");
	}

	@Test
	void rejectsSeatsAndOwnersThatDontPointAtEachOther() {
		ObjectNode document = TestDocuments.stand();
		TestDocuments.object(document, "seat-n2").put("parentId", "stage-nowhere");
		List<String> problems = LayoutDocumentValidator.validate(document);
		assertThat(problems).containsExactlyInAnyOrder(
				"Object 'seat-n2' belongs to \"stage-nowhere\", which is not a table, row or stand in this layout",
				"Object 'stand-n' lists seat \"seat-n2\", but that seat's parentId is \"stage-nowhere\"");
	}

	@Test
	void rejectsUnknownCategoriesAndLiveStatuses() {
		ObjectNode document = TestDocuments.stand();
		TestDocuments.object(document, "seat-n1").put("ticketCategoryId", "cat-gone");
		TestDocuments.object(document, "seat-n2").put("status", "sold");
		assertThat(LayoutDocumentValidator.validate(document)).containsExactlyInAnyOrder(
				"Object 'seat-n1' uses ticket category \"cat-gone\", which doesn't exist",
				"Object 'seat-n2' has status \"sold\"; a layout can only save 'held' or 'blocked'");
	}

	@Test
	void rejectsAreasWithoutAWholeCapacity() {
		ObjectNode document = TestDocuments.load("conference-standing");
		TestDocuments.object(document, "standing-65").put("capacity", -1);
		TestDocuments.object(document, "section-66").put("capacity", 12.5);
		assertThat(LayoutDocumentValidator.validate(document)).hasSize(2)
			.allMatch((problem) -> problem.endsWith("needs a whole-number capacity of 0 or more"));
	}

	@Test
	void rejectsAFloorPlanWhoseAssetIsMissing() {
		ObjectNode document = TestDocuments.load("small-theater");
		document.putObject("background").put("assetId", "img-1");
		assertThat(LayoutDocumentValidator.validate(document))
			.containsExactly("The floor plan uses asset \"img-1\", which isn't listed in assets");
	}

	@Test
	void reportsOnlyTheFirstFiftyProblems() {
		ObjectNode document = TestDocuments.load("small-theater");
		document.get("objects").values().forEach((object) -> ((ObjectNode) object).put("x", "left"));
		List<String> problems = LayoutDocumentValidator.validate(document);
		// 121 objects (1 stage, 8 rows, 112 seats), one problem each.
		assertThat(problems).hasSize(51).last().isEqualTo("... and 71 more problems");
	}

}
