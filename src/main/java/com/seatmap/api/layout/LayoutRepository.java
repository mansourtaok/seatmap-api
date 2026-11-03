package com.seatmap.api.layout;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface LayoutRepository extends JpaRepository<Layout, UUID> {

	/** Locks the layout row, so two publishes can't take the same version number. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Layout l where l.id = :id")
	Optional<Layout> findForUpdate(UUID id);

	@Query("""
			select new com.seatmap.api.layout.LayoutSummary(l.id, l.organizationId, l.name, l.description, l.draftRevision,
				(select max(v.versionNumber) from LayoutVersion v where v.layoutId = l.id), l.createdAt, l.updatedAt)
			from Layout l where l.organizationId in :organizationIds order by l.updatedAt desc""")
	List<LayoutSummary> findSummaries(Collection<UUID> organizationIds);

}
