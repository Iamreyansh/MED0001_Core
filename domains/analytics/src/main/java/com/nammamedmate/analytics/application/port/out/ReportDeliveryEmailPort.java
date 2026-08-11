package com.nammamedmate.analytics.application.port.out;

import java.util.List;

/** Scheduled report delivery (EPIC-017 SES later; logging stub for now). */
public interface ReportDeliveryEmailPort {

  void sendScheduledReport(
      List<String> recipients,
      String reportId,
      String format,
      String downloadUrl,
      byte[] attachment);
}
