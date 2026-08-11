package com.nammamedmate.analytics;

import com.nammamedmate.analytics.adapter.out.client.LoggingReportDeliveryEmailAdapter;
import com.nammamedmate.analytics.adapter.out.storage.LocalAnalyticsExportStore;
import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.analytics.application.port.out.GeographyDarkZoneOutboxPort;
import com.nammamedmate.analytics.application.port.out.ReportAuditPort;
import com.nammamedmate.analytics.application.port.out.ReportDeliveryEmailPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyticsConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock analyticsClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(AnalyticsExportPort.class)
  AnalyticsExportPort localAnalyticsExportPort() {
    return new LocalAnalyticsExportStore();
  }

  /** Default allow-all until apps/api CRM bridge overrides (@Primary). */
  @Bean
  @ConditionalOnMissingBean(AnalyticsPlanPort.class)
  AnalyticsPlanPort allowAllAnalyticsPlanPort() {
    return pharmacyId -> true;
  }

  /** No-op dark-zone outbox until apps/api wires OutboxPublisher. */
  @Bean
  @ConditionalOnMissingBean(GeographyDarkZoneOutboxPort.class)
  GeographyDarkZoneOutboxPort noopGeographyDarkZoneOutboxPort() {
    return zoneId -> {};
  }

  @Bean
  @ConditionalOnMissingBean(ReportDeliveryEmailPort.class)
  ReportDeliveryEmailPort loggingReportDeliveryEmailPort() {
    return new LoggingReportDeliveryEmailAdapter();
  }

  /** No-op audit until JDBC adapter is component-scanned (or tests replace). */
  @Bean
  @ConditionalOnMissingBean(ReportAuditPort.class)
  ReportAuditPort noopReportAuditPort() {
    return (actorId,
        actorName,
        actorRole,
        reportId,
        jobId,
        periodFrom,
        periodTo,
        rowCount,
        downloadUrl,
        generatedAt) -> {};
  }
}
