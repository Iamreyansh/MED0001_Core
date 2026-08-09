package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountModuleOverride(
    UUID id,
    UUID accountId,
    String moduleId,
    boolean enabled,
    String reason,
    UUID toggledBy,
    Instant toggledAt) {}
