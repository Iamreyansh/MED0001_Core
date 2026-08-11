package com.nammamedmate.analytics.adapter.out.client;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoggingReportDeliveryEmailAdapterTest {

  @Test
  void logsWithoutThrowing() {
    new LoggingReportDeliveryEmailAdapter()
        .sendScheduledReport(
            List.of("a@b.com"), "GMV_COMMISSION_PAYOUTS", "CSV", "url", new byte[] {1});
    new LoggingReportDeliveryEmailAdapter().sendScheduledReport(null, "X", "PDF", null, null);
  }
}
