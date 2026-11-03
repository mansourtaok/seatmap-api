package com.seatmap.api.layout;

import java.util.List;

/** A version's ticket categories and everything it sells, in document order. */
public record Inventory(List<TicketCategory> categories, List<InventoryUnit> units) {

	public int seatCount() {
		return (int) this.units.stream().filter((unit) -> unit.kind() == InventoryUnit.Kind.SEAT).count();
	}

	public int totalCapacity() {
		return this.units.stream().mapToInt(InventoryUnit::capacity).sum();
	}

}
