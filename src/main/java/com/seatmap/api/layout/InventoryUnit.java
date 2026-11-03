package com.seatmap.api.layout;

/**
 * Something a version sells: a seat (capacity 1) or an unnumbered area sold by capacity.
 * {@code unitId} is the designer's object id; {@code x}/{@code y} are its center in meters.
 */
public record InventoryUnit(String unitId, Kind kind, int capacity, String parentId, String parentName, String block,
		String rowLabel, String seatNumber, String label, String ticketCategoryId, String designStatus, double x,
		double y) {

	public enum Kind {

		SEAT, STANDING_AREA, SECTION_GA

	}

}
