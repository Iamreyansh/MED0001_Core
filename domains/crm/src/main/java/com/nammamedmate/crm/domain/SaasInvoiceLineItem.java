package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record SaasInvoiceLineItem(
    UUID id,
    UUID invoiceId,
    String description,
    String sacCode,
    long amountPaise,
    String itemType,
    Instant createdAt) {}
