package com.nammamedmate.analytics.adapter.out.client;

import com.nammamedmate.analytics.application.port.out.ReportDeliveryEmailPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging stub for scheduled report email (EPIC-017 SES later).
 *
 * <p>ponytail: upgrade → SesClientPort attachments when notification bridge is wired.
 */
public class LoggingReportDeliveryEmailAdapter implements ReportDeliveryEmailPort {

  private static final Logger log =
      LoggerFactory.getLogger(LoggingReportDeliveryEmailAdapter.class);

  @Override
  public void sendScheduledReport(
      List<String> recipients,
      String reportId,
      String format,
      String downloadUrl,
      byte[] attachment) {
    log.info(
        "report_delivery_email reportId={} format={} recipients={} urlLen={} attachmentBytes={}",
        reportId,
        format,
        recipients == null ? 0 : recipients.size(),
        downloadUrl == null ? 0 : downloadUrl.length(),
        attachment == null ? 0 : attachment.length);
  }
}
