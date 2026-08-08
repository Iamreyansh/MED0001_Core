package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Stub S3/Glacier archival for audit rows older than retention. */
public interface AuditArchivePort {

  void archive(UUID auditLogId, Instant timestamp);
}
