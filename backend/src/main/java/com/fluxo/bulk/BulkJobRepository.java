package com.fluxo.bulk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface BulkJobRepository extends JpaRepository<BulkJob, UUID> {
}
