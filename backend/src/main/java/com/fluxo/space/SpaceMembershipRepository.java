package com.fluxo.space;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SpaceMembershipRepository extends JpaRepository<SpaceMembership, UUID> {

    @Query("select m from SpaceMembership m join fetch m.role left join fetch m.user left join fetch m.group where m.space.id = :spaceId")
    List<SpaceMembership> findBySpaceId(UUID spaceId);

    /** Memberships that apply to a user: direct, or through one of their groups. */
    @Query("""
            select m from SpaceMembership m join fetch m.role
            where m.user.id = :userId
               or m.group.id in (select g.id from Group g join g.members u where u.id = :userId)
            """)
    List<SpaceMembership> findAllForUser(UUID userId);

    @Query("""
            select m from SpaceMembership m join fetch m.role
            where m.space.id = :spaceId and (m.user.id = :userId
               or m.group.id in (select g.id from Group g join g.members u where u.id = :userId))
            """)
    List<SpaceMembership> findForUserInSpace(UUID spaceId, UUID userId);
}
