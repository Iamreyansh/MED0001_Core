package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record CrmAccount(
    UUID id, UUID pharmacyId, String currentPlanName, String status, Instant createdAt) {}
