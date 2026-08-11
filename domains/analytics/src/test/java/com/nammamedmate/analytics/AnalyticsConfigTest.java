package com.nammamedmate.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.analytics.adapter.out.storage.LocalAnalyticsExportStore;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class AnalyticsConfigTest {

  @Test
  void beans() {
    AnalyticsConfig cfg = new AnalyticsConfig();
    assertThat(cfg.analyticsClock()).isEqualTo(Clock.systemUTC());
    assertThat(cfg.localAnalyticsExportPort()).isInstanceOf(LocalAnalyticsExportStore.class);
    assertThat(cfg.allowAllAnalyticsPlanPort().allowsPharmacyAnalytics(java.util.UUID.randomUUID()))
        .isTrue();
    cfg.noopGeographyDarkZoneOutboxPort().publishDarkZone(java.util.UUID.randomUUID());
    cfg.loggingReportDeliveryEmailPort()
        .sendScheduledReport(java.util.List.of("a@b.com"), "X", "CSV", "u", new byte[0]);
    cfg.noopReportAuditPort()
        .recordGeneration(
            null,
            "SCHEDULER",
            "SYSTEM",
            "X",
            java.util.UUID.randomUUID(),
            "a",
            "b",
            1,
            "u",
            java.time.Instant.now());
  }
}
