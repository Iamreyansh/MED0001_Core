package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MetricSample(
    UUID id, String metricName, Instant bucketTs, BigDecimal value, UUID zoneId) {}
