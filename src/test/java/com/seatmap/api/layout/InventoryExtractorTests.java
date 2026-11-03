package com.seatmap.api.layout;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryExtractorTests {

	@Test
	void sellsEverySeatOfARowLayout() {
		Inventory inventory = InventoryExtractor.extract(TestDocuments.load("small-theater"));
		assertThat(inventory.seatCount()).isEqualTo(112);
		assertThat(inventory.totalCapacity()).isEqualTo(112);
		InventoryUnit first = inventory.units().getFirst();
		assertThat(first.unitId()).isEqualTo("seat-6");
		assertThat(first.parentName()).isEqualTo("Row A");
		assertThat(first.rowLabel()).isEqualTo("A");
		assertThat(first.label()).isEqualTo("Row A · Seat A1");
	}

	@Test
	void labelsTableSeatsWithTheirTable() {
		Inventory inventory = InventoryExtractor.extract(TestDocuments.load("banquet-hall"));
		assertThat(inventory.seatCount()).isEqualTo(80);
		assertThat(inventory.units().getFirst().label()).isEqualTo("Round Table · Seat 1");
	}

	@Test
	void labelsStandSeatsWithBlockAndRowAndKeepsCategoryAndStatus() {
		Inventory inventory = InventoryExtractor.extract(TestDocuments.stand());
		assertThat(inventory.units()).extracting(InventoryUnit::label)
			.containsExactly("North Stand · Block N1 · Row 3 · Seat 7", "North Stand · Block N1 · Row 3 · Seat 8");
		assertThat(inventory.units()).extracting(InventoryUnit::ticketCategoryId).containsExactly("cat-vip", null);
		assertThat(inventory.units()).extracting(InventoryUnit::designStatus).containsExactly(null, "blocked");
		assertThat(inventory.categories()).singleElement()
			.extracting(TicketCategory::defaultPrice)
			.isEqualTo(new BigDecimal("120.50"));
	}

	@Test
	void sellsStandingAreasAndOnlySectionsSoldByCapacity() {
		ObjectNode document = TestDocuments.load("conference-standing");
		assertThat(InventoryExtractor.extract(document).units()).singleElement().satisfies((unit) -> {
			assertThat(unit.kind()).isEqualTo(InventoryUnit.Kind.STANDING_AREA);
			assertThat(unit.capacity()).isEqualTo(300);
			assertThat(unit.label()).isEqualTo("General Floor");
		});

		TestDocuments.object(document, "section-66").put("soldByCapacity", true);
		Inventory inventory = InventoryExtractor.extract(document);
		assertThat(inventory.units()).extracting(InventoryUnit::kind)
			.containsExactly(InventoryUnit.Kind.STANDING_AREA, InventoryUnit.Kind.SECTION_GA);
		assertThat(inventory.seatCount()).isZero();
		assertThat(inventory.totalCapacity()).isEqualTo(340);
	}

}
