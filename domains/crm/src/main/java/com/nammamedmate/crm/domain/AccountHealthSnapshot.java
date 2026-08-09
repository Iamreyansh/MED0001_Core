package com.nammamedmate.crm.domain;

import java.time.LocalDate;
import java.util.UUID;

public record AccountHealthSnapshot(
    UUID id,
    UUID accountId,
    LocalDate scoreDate,
    double overallScore,
    String healthBand,
    double productUsageScore,
    double billingHealthScore,
    double supportSatisfactionScore,
    double businessPerformanceScore) {}
