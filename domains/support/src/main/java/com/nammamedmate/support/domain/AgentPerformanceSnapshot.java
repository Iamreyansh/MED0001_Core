package com.nammamedmate.support.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AgentPerformanceSnapshot(
    UUID id,
    UUID agentId,
    LocalDate weekStart,
    int ticketsHandled,
    BigDecimal avgHandleMinutes,
    BigDecimal csatScoreAvg,
    int slaBreachCount,
    Instant createdAt) {}
