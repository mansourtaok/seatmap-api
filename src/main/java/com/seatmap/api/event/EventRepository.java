package com.seatmap.api.event;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EventRepository extends JpaRepository<Event, UUID> {

	List<Event> findByOrganizationIdInOrderByStartsAtAsc(Collection<UUID> organizationIds);

	List<Event> findByStatusOrderByStartsAtAsc(EventStatus status);

}
