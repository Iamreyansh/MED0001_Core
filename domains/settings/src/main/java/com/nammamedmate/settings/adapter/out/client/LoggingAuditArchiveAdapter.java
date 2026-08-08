package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.settings.application.port.out.AuditArchivePort;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stub S3/Glacier archival — logs only (EPIC-021 STORY-003). */
@Component
public class LoggingAuditArchiveAdapter implements AuditArchivePort {

  private static final Logger log = LoggerFactory.getLogger(LoggingAuditArchiveAdapter.class);

  @Override
  public void archive(UUID auditLogId, Instant timestamp) {
    log.info(
        "audit_archive_stub id={} timestamp={} destination=s3://stub-glacier/audit-log/",
        auditLogId,
        timestamp);
  }
}
