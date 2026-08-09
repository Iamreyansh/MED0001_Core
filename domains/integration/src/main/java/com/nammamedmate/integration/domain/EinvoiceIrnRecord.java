package com.nammamedmate.integration.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EinvoiceIrnRecord(
    UUID id,
    UUID pharmacyId,
    UUID platformInvoiceId,
    String irn,
    String ackNumber,
    Instant ackDate,
    String sellerGstin,
    String buyerGstin,
    String invoiceNumber,
    LocalDate invoiceDate,
    String documentType,
    String financialYear,
    BigDecimal totalInvoiceValue,
    String qrCodeUrl,
    String signedInvoiceJson,
    String status,
    String cancelReasonCode,
    String cancelRemark,
    Instant generatedAt,
    Instant cancelledAt) {}
