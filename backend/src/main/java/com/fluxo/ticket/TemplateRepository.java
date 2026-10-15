package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface TemplateRepository extends JpaRepository<TemplateController.Template, UUID> {
    List<TemplateController.Template> findBySpaceIdOrderByNameAsc(UUID spaceId);
}
