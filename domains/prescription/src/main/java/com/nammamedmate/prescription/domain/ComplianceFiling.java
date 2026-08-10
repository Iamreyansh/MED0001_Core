package com.nammamedmate.prescription.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ComplianceFiling(
    UUID id,
    String filingType,
    LocalDate periodFrom,
    LocalDate periodTo,
    LocalDate dueDate,
    String status,
    String generatedReportS3Key,
    String generatedReportFormat,
    Instant generatedAt,
    UUID filedBy,
    Instant filedAt,
    String referenceNumber,
    boolean archived,
    Instant overdueAlertedAt,
    Instant overdueEscalationAt,
    Instant createdAt,
    Instant updatedAt) {}
