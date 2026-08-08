package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminOrderExportJob(
    UUID id,
    UUID requestedBy,
    String filtersJson,
    Integer rowCount,
    ExportJobStatus status,
    String s3Key,
    Instant createdAt,
    Instant completedAt) {}
