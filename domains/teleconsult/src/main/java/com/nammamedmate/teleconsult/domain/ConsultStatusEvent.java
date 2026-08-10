package com.nammamedmate.teleconsult.domain;

import java.time.Instant;
import java.util.UUID;

/** Audit trail for admin-driven consult status transitions (EPIC-009 STORY-003). */
public record ConsultStatusEvent(
    UUID id,
    UUID consultId,
    String fromStatus,
    String toStatus,
    UUID actorId,
    String notes,
    Instant createdAt) {}
