package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SloComplianceRecord(
    UUID id,
    String sloName,
    LocalDate periodFrom,
    LocalDate periodTo,
    BigDecimal targetPct,
    BigDecimal actualPct,
    boolean compliant,
    BigDecimal errorBudgetConsumedPct,
    int incidentCount,
    Instant recordedAt) {}
