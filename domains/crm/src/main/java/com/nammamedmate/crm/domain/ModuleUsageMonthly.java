package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ModuleUsageMonthly(
    UUID id,
    UUID accountId,
    String moduleId,
    LocalDate eventMonth,
    int eventCount,
    Instant lastActiveAt) {}
