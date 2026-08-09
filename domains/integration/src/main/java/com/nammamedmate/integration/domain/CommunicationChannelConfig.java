package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record CommunicationChannelConfig(
    String channel,
    boolean enabled,
    String provider,
    String fallbackProvider,
    String secretsManagerKey,
    int dailySendLimit,
    int dailySentCount,
    String currentStatus,
    Instant lastHealthCheckAt,
    UUID updatedBy,
    Instant updatedAt) {}
