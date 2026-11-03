package com.seatmap.api.layout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LayoutVersionRepository extends JpaRepository<LayoutVersion, UUID> {

	Optional<LayoutVersion> findByLayoutIdAndVersionNumber(UUID layoutId, int versionNumber);

	boolean existsByLayoutId(UUID layoutId);

	@Query("select coalesce(max(v.versionNumber), 0) from LayoutVersion v where v.layoutId = :layoutId")
	int findLatestVersionNumber(UUID layoutId);

	@Query("""
			select new com.seatmap.api.layout.VersionSummary(v.versionNumber, v.seatCount, v.totalCapacity, v.publishedAt)
			from LayoutVersion v where v.layoutId = :layoutId order by v.versionNumber desc""")
	List<VersionSummary> findSummaries(UUID layoutId);

}
