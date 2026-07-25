package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record LoginAuditRecord(
    UUID id,
    String actorType,
    String identifier,
    UUID staffId,
    boolean success,
    String failureReason,
    String ipAddress,
    String userAgent,
    Instant createdAt) {}
