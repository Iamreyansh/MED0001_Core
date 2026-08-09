package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record ChurnSurvey(
    UUID id, UUID accountId, String reason, String notes, UUID loggedBy, Instant createdAt) {}
