package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AccountingSyncJob(
    UUID id,
    UUID pharmacyId,
    String accountingSystem,
    String syncType,
    LocalDate periodFrom,
    LocalDate periodTo,
    String status,
    int recordsProcessed,
    int recordsSynced,
    int recordsFailed,
    List<Map<String, Object>> errors,
    String triggeredBy,
    Instant queuedAt,
    Instant startedAt,
    Instant completedAt) {

  public AccountingSyncJob {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }
}
