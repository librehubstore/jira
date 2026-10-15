package com.fluxo.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface QuickFilterRepository extends JpaRepository<QuickFilter, UUID> {

    List<QuickFilter> findByBoardIdOrderByPositionAsc(UUID boardId);

    @Modifying
    @Query("delete from QuickFilter q where q.board.id = :boardId")
    void deleteByBoard(UUID boardId);
}
