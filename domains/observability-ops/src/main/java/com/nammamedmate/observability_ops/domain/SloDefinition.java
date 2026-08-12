package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;

public record SloDefinition(
    String sloName,
    String description,
    BigDecimal targetPct,
    String metricName,
    int measurementWindowDays) {}
