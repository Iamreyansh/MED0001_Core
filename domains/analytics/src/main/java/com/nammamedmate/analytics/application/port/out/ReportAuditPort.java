package com.nammamedmate.analytics.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit append for report generation events (writes platform audit_log). */
public interface ReportAuditPort {

  void recordGeneration(
      UUID actorId,
      String actorName,
      String actorRole,
      String reportId,
      UUID jobId,
      String periodFrom,
      String periodTo,
      int rowCount,
      String downloadUrl,
      Instant generatedAt);
}
