package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record CashfreeBeneficiary(
    UUID id,
    String entityType,
    UUID entityId,
    String cashfreeContactId,
    String beneficiaryId,
    String bankName,
    String accountLast4,
    String ifsc,
    String accountHolderName,
    boolean active,
    Instant createdAt) {}
