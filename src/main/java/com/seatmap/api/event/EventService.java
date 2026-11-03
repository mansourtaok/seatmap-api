package com.seatmap.api.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.seatmap.api.event.EventViews.AreaAvailability;
import com.seatmap.api.event.EventViews.Availability;
import com.seatmap.api.event.EventViews.CategoryAvailability;
import com.seatmap.api.event.EventViews.EventView;
import com.seatmap.api.event.EventViews.Price;
import com.seatmap.api.event.ReservationRepository.SellableUnit;
import com.seatmap.api.account.Access;
import com.seatmap.api.account.Role;
import com.seatmap.api.event.EventViews.PublicEvent;
import com.seatmap.api.layout.Layout;
import com.seatmap.api.layout.LayoutService;
import com.seatmap.api.layout.LayoutVersion;
import com.seatmap.api.layout.PublishedLayouts;
import com.seatmap.api.layout.StoredAsset;
import com.seatmap.api.web.ConflictException;
import com.seatmap.api.web.InvalidRequestException;
import com.seatmap.api.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Events, their prices, and what they have left to sell. Members of the event's organization
 * see it in any state; everyone else only while it is on sale.
 */
@Service
public class EventService {

	private final EventRepository events;

	private final PriceRepository prices;

	private final ReservationRepository reservations;

	private final LayoutService layouts;

	private final PublishedLayouts publishedLayouts;

	private final Access access;

	EventService(EventRepository events, PriceRepository prices, ReservationRepository reservations,
			LayoutService layouts, PublishedLayouts publishedLayouts, Access access) {
		this.events = events;
		this.prices = prices;
		this.reservations = reservations;
		this.layouts = layouts;
		this.publishedLayouts = publishedLayouts;
		this.access = access;
	}

	/** Creates a draft event at the version's default prices. */
	@Transactional
	public EventView create(EventRequests.CreateEvent request) {
		requireEndAfterStart(request.startsAt(), request.endsAt());
		Layout layout = this.layouts.find(request.layoutId(), Role.EDITOR);
		LayoutVersion version = this.layouts.version(request.layoutId(), request.version());
		Instant now = now();
		Event event = new Event(UUID.randomUUID(), layout.getOrganizationId(), request.layoutId(), version.getId(),
				request.version(), now);
		event.update(request.name(), request.startsAt(), request.endsAt(), request.currency(), EventStatus.DRAFT, now);
		this.events.saveAndFlush(event);
		this.prices.copyDefaults(event.getId(), version.getId());
		return view(event);
	}

	/** The events of the current user's organizations, soonest first, without prices. */
	@Transactional(readOnly = true)
	public List<EventView> list() {
		List<UUID> organizations = this.access.organizationIds();
		return organizations.isEmpty() ? List.of()
				: this.events.findByOrganizationIdInOrderByStartsAtAsc(organizations)
					.stream()
					.map((event) -> view(event, null))
					.toList();
	}

	@Transactional(readOnly = true)
	public EventView get(UUID id) {
		return view(find(id, Role.VIEWER));
	}

	/** Every event on sale, soonest first, as buyers see them. */
	@Transactional(readOnly = true)
	public List<EventView> listOnSale() {
		return this.events.findByStatusOrderByStartsAtAsc(EventStatus.ON_SALE).stream().map(this::view).toList();
	}

	/** An on-sale event with its layout, for drawing the seat map. */
	@Transactional(readOnly = true)
	public PublicEvent publicEvent(UUID id) {
		Event event = findOnSale(id);
		return new PublicEvent(view(event), this.publishedLayouts.document(event.getVersionId()).orElseThrow());
	}

	/** A floor-plan image of an on-sale event. */
	@Transactional(readOnly = true)
	public StoredAsset publicAsset(UUID id, String assetId) {
		Event event = findOnSale(id);
		return this.publishedLayouts.asset(event.getVersionId(), assetId)
			.orElseThrow(() -> new NotFoundException("This event has no image " + assetId));
	}

	/** Replaces name, dates, currency and status, provided the event is still at {@code expectedRevision}. */
	@Transactional
	public EventView update(UUID id, int expectedRevision, EventRequests.UpdateEvent request) {
		Event event = find(id, Role.EDITOR);
		if (event.getRevision() != expectedRevision) {
			throw new ConflictException("The event is at revision %d, not %d; reload it before saving"
				.formatted(event.getRevision(), expectedRevision));
		}
		requireEndAfterStart(request.startsAt(), request.endsAt());
		if (!event.getStatus().canBecome(request.status())) {
			throw new ConflictException("An event that is %s can't become %s".formatted(event.getStatus(),
					request.status()));
		}
		if (!event.getCurrency().equals(request.currency()) && this.reservations.existsForEvent(id)) {
			throw new ConflictException("The currency can't change once tickets have been reserved");
		}
		event.update(request.name(), request.startsAt(), request.endsAt(), request.currency(), request.status(), now());
		// Flushing bumps the revision now, so the caller gets the new ETag.
		this.events.saveAndFlush(event);
		return view(event);
	}

	/** Changes prices. Reservations keep the price they were made at. */
	@Transactional
	public EventView updatePrices(UUID id, EventRequests.UpdatePrices request) {
		Event event = find(id, Role.EDITOR);
		Set<String> known = this.prices.categoryIds(event.getVersionId());
		List<String> unknown = request.prices()
			.keySet()
			.stream()
			.filter((categoryId) -> !known.contains(categoryId))
			.sorted()
			.toList();
		if (!unknown.isEmpty()) {
			throw new InvalidRequestException("The layout version has no such ticket categories",
					unknown.stream().map((categoryId) -> "Unknown ticket category '" + categoryId + "'").toList());
		}
		this.prices.update(id, request.prices());
		return view(event);
	}

	/** Capacity, sold, held and available places per category and per standing area. */
	@Transactional(readOnly = true)
	public Availability availability(UUID id) {
		Event event = findVisible(id);
		Instant now = now();
		Map<String, String> takenSeats = this.reservations.findTakenSeats(id, now);
		Map<String, int[]> places = this.reservations.countPlaces(id, now);

		Map<String, int[]> byCategory = new LinkedHashMap<>();
		List<Price> categories = this.prices.find(id);
		categories.forEach((price) -> byCategory.put(price.categoryId(), new int[4]));
		List<AreaAvailability> areas = new ArrayList<>();
		int withoutCategory = 0;
		for (SellableUnit unit : this.reservations.findAllUnits(event.getVersionId())) {
			int[] counts = (unit.categoryId() != null) ? byCategory.get(unit.categoryId()) : null;
			if (counts == null) {
				withoutCategory += unit.capacity();
				continue;
			}
			// counts: capacity, sold, held, unavailable
			counts[0] += unit.capacity();
			if (unit.isSeat()) {
				String taken = takenSeats.get(unit.unitId());
				if (unit.designStatus() != null) {
					counts[3]++;
				}
				else if ("SOLD".equals(taken)) {
					counts[1]++;
				}
				else if ("HELD".equals(taken)) {
					counts[2]++;
				}
			}
			else {
				int[] soldHeld = places.getOrDefault(unit.unitId(), new int[2]);
				counts[1] += soldHeld[0];
				counts[2] += soldHeld[1];
				areas.add(new AreaAvailability(unit.unitId(), unit.label(), unit.categoryId(), unit.capacity(),
						soldHeld[0], soldHeld[1], unit.capacity() - soldHeld[0] - soldHeld[1]));
			}
		}
		List<CategoryAvailability> perCategory = categories.stream().map((price) -> {
			int[] counts = byCategory.get(price.categoryId());
			return new CategoryAvailability(price.categoryId(), price.name(), counts[0], counts[1], counts[2],
					counts[3], counts[0] - counts[1] - counts[2] - counts[3]);
		}).toList();
		return new Availability(perCategory, areas, withoutCategory);
	}

	/**
	 * Seats that aren't available, as the designer's buyer view takes them: seat id to
	 * {@code sold}, {@code held} or {@code blocked}. Seats not listed are available.
	 */
	@Transactional(readOnly = true)
	public Map<String, String> seatStatuses(UUID id) {
		Event event = findVisible(id);
		Map<String, String> statuses = new LinkedHashMap<>(this.reservations.findDesignStatuses(event.getVersionId()));
		this.reservations.findTakenSeats(id, now())
			.forEach((unitId, status) -> statuses.put(unitId, "SOLD".equals(status) ? "sold" : "held"));
		return statuses;
	}

	/** The event, with no access check. */
	Event find(UUID id) {
		return this.events.findById(id).orElseThrow(() -> eventNotFound(id));
	}

	/** The event, if the current user has at least {@code role} in its organization. */
	Event find(UUID id, Role role) {
		Event event = find(id);
		this.access.require(event.getOrganizationId(), role, () -> eventNotFound(id));
		return event;
	}

	/** The event, for its members or, while it is on sale, for anyone. */
	private Event findVisible(UUID id) {
		Event event = find(id);
		if (event.getStatus() != EventStatus.ON_SALE && !this.access.has(event.getOrganizationId(), Role.VIEWER)) {
			throw eventNotFound(id);
		}
		return event;
	}

	private Event findOnSale(UUID id) {
		Event event = find(id);
		if (event.getStatus() != EventStatus.ON_SALE) {
			throw eventNotFound(id);
		}
		return event;
	}

	private static NotFoundException eventNotFound(UUID id) {
		return new NotFoundException("No event " + id);
	}

	private EventView view(Event event) {
		return view(event, this.prices.find(event.getId()));
	}

	private static EventView view(Event event, List<Price> prices) {
		return new EventView(event.getId(), event.getOrganizationId(), event.getLayoutId(), event.getVersionNumber(),
				event.getName(), event.getStartsAt(), event.getEndsAt(), event.getCurrency(), event.getStatus(),
				event.getRevision(), prices);
	}

	private static void requireEndAfterStart(Instant startsAt, Instant endsAt) {
		if (endsAt != null && !endsAt.isAfter(startsAt)) {
			throw new InvalidRequestException("endsAt must be after startsAt");
		}
	}

	static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
