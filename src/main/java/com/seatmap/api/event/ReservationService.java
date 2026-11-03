package com.seatmap.api.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.seatmap.api.SeatmapProperties;
import com.seatmap.api.event.EventRequests.Hold;
import com.seatmap.api.event.EventViews.ReservationItem;
import com.seatmap.api.event.EventViews.ReservationStatus;
import com.seatmap.api.event.EventViews.ReservationView;
import com.seatmap.api.event.ReservationRepository.SellableUnit;
import com.seatmap.api.web.ConflictException;
import com.seatmap.api.web.InvalidRequestException;
import com.seatmap.api.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holding, confirming and releasing seats and standing places. A hold is all or nothing:
 * if any part of it is taken, nothing is held.
 */
@Service
public class ReservationService {

	private final EventService events;

	private final PriceRepository prices;

	private final ReservationRepository reservations;

	private final SeatmapProperties properties;

	ReservationService(EventService events, PriceRepository prices, ReservationRepository reservations,
			SeatmapProperties properties) {
		this.events = events;
		this.prices = prices;
		this.reservations = reservations;
		this.properties = properties;
	}

	@Transactional
	public ReservationView hold(UUID eventId, Hold request) {
		Event event = this.events.find(eventId);
		if (event.getStatus() != EventStatus.ON_SALE) {
			throw new ConflictException("Tickets for this event aren't on sale");
		}
		// Claim in id order, so two overlapping holds can't each wait for the other.
		List<Hold.Item> items = request.items().stream().sorted(Comparator.comparing(Hold.Item::unitId)).toList();
		Map<String, SellableUnit> units = this.reservations.findUnits(event.getVersionId(),
				items.stream().map(Hold.Item::unitId).toList());
		checkRequest(items, units);

		Map<String, BigDecimal> priceByCategory = this.prices.findByCategory(eventId);
		List<String> unavailable = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Hold.Item item : items) {
			SellableUnit unit = units.get(item.unitId());
			if (unit.designStatus() != null || unit.categoryId() == null
					|| !priceByCategory.containsKey(unit.categoryId())) {
				unavailable.add(unit.label() + " isn't for sale");
			}
			else {
				total = total.add(priceByCategory.get(unit.categoryId()).multiply(BigDecimal.valueOf(item.quantity())));
			}
		}
		if (!unavailable.isEmpty()) {
			throw new ConflictException("Some of these places aren't available", unavailable);
		}

		Instant now = EventService.now();
		Instant expiresAt = now.plus(this.properties.holdDuration());
		UUID reservationId = UUID.randomUUID();
		this.reservations.insert(reservationId, eventId, expiresAt, total, now);
		for (Hold.Item item : items) {
			SellableUnit unit = units.get(item.unitId());
			if (unit.isSeat()) {
				if (!this.reservations.claimSeat(eventId, unit.unitId(), reservationId, expiresAt, now)) {
					unavailable.add(unit.label() + " is taken");
				}
			}
			else {
				this.reservations.lockArea(eventId, unit.unitId());
				int left = unit.capacity() - this.reservations.countTaken(eventId, unit.unitId(), reservationId, now);
				if (item.quantity() > left) {
					unavailable.add("%s has only %d left".formatted(unit.label(), Math.max(left, 0)));
				}
			}
			this.reservations.insertItem(reservationId, eventId, unit.unitId(), item.quantity(), unit.categoryId(),
					priceByCategory.get(unit.categoryId()));
		}
		if (!unavailable.isEmpty()) {
			// Rolls back everything claimed above.
			throw new ConflictException("Some of these places aren't available", unavailable);
		}
		return view(reservationId);
	}

	@Transactional(readOnly = true)
	public ReservationView get(UUID id) {
		return view(id);
	}

	/** Sells what the reservation holds. Confirming twice is harmless. */
	@Transactional
	public ReservationView confirm(UUID id) {
		ReservationRepository.Row reservation = this.reservations.findForUpdate(id).orElseThrow(() -> notFound(id));
		if (reservation.status() == ReservationStatus.CONFIRMED) {
			return view(id);
		}
		Instant now = EventService.now();
		if (reservation.status() == ReservationStatus.RELEASED || !reservation.expiresAt().isAfter(now)) {
			throw new ConflictException("This reservation has expired or was released");
		}
		Event event = this.events.find(reservation.eventId());
		if (event.getStatus() == EventStatus.CANCELLED) {
			throw new ConflictException("This event was cancelled");
		}
		List<ReservationRepository.Item> items = this.reservations.findItems(id);
		long seats = items.stream().filter((item) -> "SEAT".equals(item.kind())).count();
		// Fewer seats than held means a hold ran out and someone else took the seat meanwhile.
		if (this.reservations.sellSeats(id) != seats) {
			throw new ConflictException("This reservation has expired");
		}
		List<ReservationRepository.Item> areaItems = items.stream()
			.filter((item) -> !"SEAT".equals(item.kind()))
			.toList();
		Map<String, SellableUnit> areas = this.reservations.findUnits(event.getVersionId(),
				areaItems.stream().map(ReservationRepository.Item::unitId).toList());
		for (ReservationRepository.Item item : areaItems) {
			// The same race for areas: once expired, its places may have been counted as free.
			this.reservations.lockArea(event.getId(), item.unitId());
			int taken = this.reservations.countTaken(event.getId(), item.unitId(), id, now);
			if (taken + item.quantity() > areas.get(item.unitId()).capacity()) {
				throw new ConflictException("This reservation has expired");
			}
		}
		this.reservations.markConfirmed(id, now);
		return view(id);
	}

	/** Gives back what a reservation holds. Releasing twice is harmless; sold tickets can't be released. */
	@Transactional
	public void release(UUID id) {
		ReservationRepository.Row reservation = this.reservations.findForUpdate(id).orElseThrow(() -> notFound(id));
		if (reservation.status() == ReservationStatus.CONFIRMED) {
			throw new ConflictException("This reservation is confirmed; its tickets are sold");
		}
		if (reservation.status() == ReservationStatus.HELD) {
			this.reservations.freeSeats(id);
			this.reservations.markReleased(id, EventService.now());
		}
	}

	/** Releases holds that have run out. Seats count as free once expired anyway; this tidies up. */
	@Transactional
	public int releaseExpired() {
		return this.reservations.releaseExpired(EventService.now());
	}

	private void checkRequest(List<Hold.Item> items, Map<String, SellableUnit> units) {
		List<String> problems = new ArrayList<>();
		if (items.size() > this.properties.maxReservationItems()) {
			problems.add("A reservation can hold at most %d seats and areas".formatted(
					this.properties.maxReservationItems()));
		}
		Set<String> seen = new HashSet<>();
		for (Hold.Item item : items) {
			SellableUnit unit = units.get(item.unitId());
			if (!seen.add(item.unitId())) {
				problems.add("'" + item.unitId() + "' is listed more than once");
			}
			else if (unit == null) {
				problems.add("'" + item.unitId() + "' isn't sold at this event");
			}
			else if (unit.isSeat() && item.quantity() != 1) {
				problems.add(unit.label() + " is a seat, so its quantity must be 1");
			}
		}
		if (!problems.isEmpty()) {
			throw new InvalidRequestException("The reservation request is invalid", problems);
		}
	}

	private ReservationView view(UUID id) {
		ReservationRepository.Row reservation = this.reservations.find(id).orElseThrow(() -> notFound(id));
		// A hold that ran out is released, even before the clean-up job gets to it.
		ReservationStatus status = (reservation.status() == ReservationStatus.HELD
				&& !reservation.expiresAt().isAfter(EventService.now())) ? ReservationStatus.RELEASED
						: reservation.status();
		List<ReservationItem> items = this.reservations.findItems(id)
			.stream()
			.map((item) -> new ReservationItem(item.unitId(), item.label(), item.quantity(), item.categoryId(),
					item.unitPrice()))
			.toList();
		return new ReservationView(id, reservation.eventId(), status, reservation.expiresAt(), reservation.total(),
				this.events.find(reservation.eventId()).getCurrency(), items);
	}

	private static NotFoundException notFound(UUID id) {
		return new NotFoundException("No reservation " + id);
	}

}
