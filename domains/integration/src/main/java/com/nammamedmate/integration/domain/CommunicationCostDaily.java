package com.nammamedmate.integration.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CommunicationCostDaily(
    UUID id,
    LocalDate date,
    String channel,
    String provider,
    int sentCount,
    int deliveredCount,
    int fallbackSentCount,
    BigDecimal costRs,
    Instant createdAt) {}
