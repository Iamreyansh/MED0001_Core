package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record SavePlay(
    UUID id,
    UUID accountId,
    String actionType,
    String outcome,
    String notes,
    UUID loggedBy,
    Instant createdAt) {}
