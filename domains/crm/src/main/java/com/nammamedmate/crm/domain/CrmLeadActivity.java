package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record CrmLeadActivity(
    UUID id,
    UUID leadId,
    String event,
    String stageFrom,
    String stageTo,
    String notes,
    UUID actorId,
    String actorName,
    Instant createdAt) {}
