package com.fluxo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findBySpaceIdOrderByNameAsc(UUID spaceId);

    /** Boards of the space, including multi-space boards that cover it. */
    @Query("select distinct b from Board b left join b.extraSpaces x where b.space.id = :spaceId or x.id = :spaceId order by b.name")
    List<Board> findCovering(UUID spaceId);
}
