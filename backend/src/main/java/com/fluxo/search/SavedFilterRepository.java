package com.fluxo.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {

    /** Filters the user may see: own, shared with everyone, with one of their groups, or with a visible space. */
    default List<SavedFilter> findVisible(UUID userId, Collection<UUID> spaceIds) {
        return findVisible(userId, spaceIds.isEmpty() ? Set.of(new UUID(0, 0)) : spaceIds,
                SavedFilter.Scope.ALL, SavedFilter.Scope.GROUP, SavedFilter.Scope.SPACE);
    }

    @Query("""
            select f from SavedFilter f join fetch f.owner
            where f.owner.id = :userId
               or f.shareScope = :all
               or (f.shareScope = :group and f.shareGroup.id in (select g.id from Group g join g.members m where m.id = :userId))
               or (f.shareScope = :space and f.shareSpace.id in :spaceIds)
            order by lower(f.name)
            """)
    List<SavedFilter> findVisible(UUID userId, Collection<UUID> spaceIds, SavedFilter.Scope all, SavedFilter.Scope group,
                                  SavedFilter.Scope space);
}
