package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record CrmLead(
    UUID id,
    String pharmacyName,
    String contactName,
    String phone,
    String email,
    String source,
    String stage,
    int winProbability,
    Long estimatedMrrPaise,
    String targetPlan,
    UUID assignedRepId,
    String notes,
    String lostReason,
    Instant wonAt,
    Instant lostAt,
    Integer salesCycleDays,
    UUID linkedAccountId,
    UUID pharmacyId,
    Instant createdAt,
    Instant updatedAt) {}
