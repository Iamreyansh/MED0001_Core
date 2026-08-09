package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountingIntegration(
    UUID id,
    UUID pharmacyId,
    String accountingSystem,
    String zohoOrganizationId,
    String zohoOrganizationName,
    String zohoAccessToken,
    String zohoRefreshToken,
    Instant zohoTokenExpiresAt,
    String apiKeyStatus,
    boolean autoSyncEnabled,
    String syncFrequency,
    Instant nextSyncAt,
    Instant lastSyncAt,
    String lastSyncStatus,
    Instant createdAt,
    Instant updatedAt) {}
