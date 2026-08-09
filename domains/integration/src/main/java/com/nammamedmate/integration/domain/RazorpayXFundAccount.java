package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record RazorpayXFundAccount(
    UUID id,
    String entityType,
    UUID entityId,
    String razorpayxContactId,
    String fundAccountId,
    String bankName,
    String accountLast4,
    String ifsc,
    String accountHolderName,
    boolean active,
    Instant createdAt) {}
