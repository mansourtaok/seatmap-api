package com.seatmap.api.layout;

import java.math.BigDecimal;

/** A version's ticket category. Events will set their own prices; this is the layout's default. */
public record TicketCategory(String id, String name, String color, BigDecimal defaultPrice) {
}
