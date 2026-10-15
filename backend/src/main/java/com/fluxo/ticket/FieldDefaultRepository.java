package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface FieldDefaultRepository extends JpaRepository<FieldDefault, UUID> {

    @Query("select d from FieldDefault d left join fetch d.ticketType where d.space.id = :spaceId")
    List<FieldDefault> findBySpace(UUID spaceId);

    @Modifying
    @Query("delete from FieldDefault d where d.space.id = :spaceId")
    void deleteBySpace(UUID spaceId);
}
