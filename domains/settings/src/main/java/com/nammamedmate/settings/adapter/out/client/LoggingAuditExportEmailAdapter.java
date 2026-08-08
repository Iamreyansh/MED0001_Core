package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.settings.application.port.out.AuditExportEmailPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logging stub — export download email (EPIC-021 STORY-003). */
@Component
public class LoggingAuditExportEmailAdapter implements AuditExportEmailPort {

  private static final Logger log = LoggerFactory.getLogger(LoggingAuditExportEmailAdapter.class);

  @Override
  public void sendExportReady(UUID actorId, UUID exportJobId, String downloadUrl) {
    log.info(
        "audit_export_email actorId={} jobId={} urlLen={} expiry=1h",
        actorId,
        exportJobId,
        downloadUrl == null ? 0 : downloadUrl.length());
  }
}
