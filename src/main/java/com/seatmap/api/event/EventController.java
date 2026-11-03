package com.seatmap.api.event;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.seatmap.api.event.EventViews.Availability;
import com.seatmap.api.event.EventViews.EventView;
import com.seatmap.api.event.EventViews.PublicEvent;
import com.seatmap.api.event.EventViews.ReservationView;
import com.seatmap.api.layout.StoredAsset;
import com.seatmap.api.web.Revisions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events, their prices and availability, and reservations against them. {@code /api/public}
 * serves on-sale events to buyers, who need no login.
 */
@RestController
class EventController {

	private final EventService events;

	private final ReservationService reservations;

	EventController(EventService events, ReservationService reservations) {
		this.events = events;
		this.reservations = reservations;
	}

	@PostMapping("/api/events")
	ResponseEntity<EventView> create(@Valid @RequestBody EventRequests.CreateEvent request) {
		EventView event = this.events.create(request);
		return ResponseEntity.created(URI.create("/api/events/" + event.id()))
			.eTag(Revisions.eTag(event.revision()))
			.body(event);
	}

	@GetMapping("/api/events")
	List<EventView> list() {
		return this.events.list();
	}

	@GetMapping("/api/events/{id}")
	ResponseEntity<EventView> get(@PathVariable UUID id) {
		EventView event = this.events.get(id);
		return ResponseEntity.ok().eTag(Revisions.eTag(event.revision())).body(event);
	}

	@PutMapping("/api/events/{id}")
	ResponseEntity<EventView> update(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
			@Valid @RequestBody EventRequests.UpdateEvent request) {
		EventView event = this.events.update(id, Revisions.fromIfMatch(ifMatch), request);
		return ResponseEntity.ok().eTag(Revisions.eTag(event.revision())).body(event);
	}

	@PutMapping("/api/events/{id}/prices")
	EventView updatePrices(@PathVariable UUID id, @Valid @RequestBody EventRequests.UpdatePrices request) {
		return this.events.updatePrices(id, request);
	}

	@GetMapping("/api/events/{id}/availability")
	Availability availability(@PathVariable UUID id) {
		return this.events.availability(id);
	}

	@GetMapping("/api/events/{id}/seat-statuses")
	Map<String, String> seatStatuses(@PathVariable UUID id) {
		return this.events.seatStatuses(id);
	}

	@PostMapping("/api/events/{id}/reservations")
	ResponseEntity<ReservationView> hold(@PathVariable UUID id, @Valid @RequestBody EventRequests.Hold request,
			HttpServletRequest servletRequest) {
		// Behind a proxy, set server.forward-headers-strategy so this is the client, not the proxy.
		ReservationView reservation = this.reservations.hold(id, request, servletRequest.getRemoteAddr());
		return ResponseEntity.created(URI.create("/api/reservations/" + reservation.id())).body(reservation);
	}

	@GetMapping("/api/public/events")
	List<EventView> onSale() {
		return this.events.listOnSale();
	}

	@GetMapping("/api/public/events/{id}")
	PublicEvent publicEvent(@PathVariable UUID id) {
		return this.events.publicEvent(id);
	}

	@GetMapping("/api/public/events/{id}/assets/{assetId}")
	ResponseEntity<byte[]> publicAsset(@PathVariable UUID id, @PathVariable String assetId) {
		StoredAsset asset = this.events.publicAsset(id, assetId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(asset.mimeType()))
			.eTag(asset.sha256())
			.header("X-Content-Type-Options", "nosniff")
			.body(asset.bytes());
	}

	@GetMapping("/api/reservations/{id}")
	ReservationView reservation(@PathVariable UUID id) {
		return this.reservations.get(id);
	}

	@PostMapping("/api/reservations/{id}/confirm")
	ReservationView confirm(@PathVariable UUID id) {
		return this.reservations.confirm(id);
	}

	@DeleteMapping("/api/reservations/{id}")
	ResponseEntity<Void> release(@PathVariable UUID id) {
		this.reservations.release(id);
		return ResponseEntity.noContent().build();
	}

}
