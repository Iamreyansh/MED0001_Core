package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.adapter.out.messaging.OutboxNotificationDispatchAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubMetricSourceAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubOnlineAdminDirectoryAdapter;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Samples;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Slos;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MonitoringStory001AcTest {

  private static final Instant T0 = Instant.parse("2026-07-24T10:00:00Z");

  private StubMetricSourceAdapter source;
  private Samples samples;
  private Alerts alerts;
  private Slos slos;
  private OutboxNotificationDispatchAdapter notify;
  private StubOnlineAdminDirectoryAdapter admins;
  private Clock clock;
  private MetricCollectionService collection;
  private MonitoringQueryService query;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal finance;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    source = new StubMetricSourceAdapter();
    // Keep defaults above GMV drop threshold and without zone-dark streak.
    source.setGmvCurrentHourPaise(800_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    source.setZones(
        List.of(
            new com.nammamedmate.observability_ops.application.port.out.MetricSourcePort
                .ZoneRiderSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Indiranagar", 5, 3)));
    samples = new Samples();
    alerts = new Alerts();
    slos = new Slos();
    notify = new OutboxNotificationDispatchAdapter(emptyOutbox());
    admins = new StubOnlineAdminDirectoryAdapter();
    clock = Clock.fixed(T0, ZoneOffset.UTC);
    collection = new MetricCollectionService(source, samples, alerts, slos, notify, admins, clock);
    query = new MonitoringQueryService(source, samples, alerts, slos, clock);
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  @DisplayName("AC-001 realtime data_age_seconds < 120")
  void ac001_realtimeFresh() {
    collection.collectAndEvaluate();
    Map<String, Object> data = query.realtime(superAdmin);
    assertThat(((Number) data.get("data_age_seconds")).longValue()).isLessThan(120);
    assertThat(data.get("gmv_last_hour_paise")).isEqualTo(800_000L);
  }

  @Test
  @DisplayName("AC-002 GMV_DROP CRITICAL when < 50% DoW avg")
  void ac002_gmvDropCritical() {
    source.setGmvCurrentHourPaise(400_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    collection.collectAndEvaluate();
    MonitoringAlert alert = alerts.findOpen(AlertType.GMV_DROP, null).orElseThrow();
    assertThat(alert.severity()).isEqualTo(AlertSeverity.CRITICAL);
    assertThat(alert.type()).isEqualTo(AlertType.GMV_DROP);
  }

  @Test
  @DisplayName("AC-003 CRITICAL pages push+SMS via notify port")
  void ac003_criticalPaging() {
    UUID adminId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    admins.setOnline(List.of(adminId));
    source.setGmvCurrentHourPaise(100_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    collection.collectAndEvaluate();
    assertThat(notify.dispatched()).hasSize(1);
    assertThat(notify.dispatched().getFirst().adminIds()).containsExactly(adminId);
    assertThat(notify.dispatched().getFirst().alertType()).isEqualTo("GMV_DROP");
    assertThat(notify.dispatched().getFirst().channels()).containsExactly("push", "sms");
    assertThat(notify.dispatched().getFirst().priority()).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("AC-004 auto-resolve after 2 healthy intervals")
  void ac004_autoResolve() {
    source.setGmvCurrentHourPaise(100_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    collection.collectAndEvaluate();
    UUID id = alerts.findOpen(AlertType.GMV_DROP, null).orElseThrow().id();
    source.setGmvCurrentHourPaise(800_000);
    collection.collectAndEvaluate();
    assertThat(alerts.findById(id).orElseThrow().resolvedAt()).isNull();
    collection.collectAndEvaluate();
    MonitoringAlert resolved = alerts.findById(id).orElseThrow();
    assertThat(resolved.resolvedAt()).isNotNull();
    assertThat(resolved.resolutionReason()).isEqualTo("AUTO_RESOLVED");
  }

  @Test
  @DisplayName("AC-005 metrics null gaps not zero")
  void ac005_nullGaps() {
    Instant bucket = T0.truncatedTo(ChronoUnit.MINUTES);
    samples.upsert("sla_pct", bucket.minus(2, ChronoUnit.MINUTES), new BigDecimal("94.2"), null);
    Map<String, Object> data = query.metrics(superAdmin, "sla_pct", 60);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("data_points");
    assertThat(points).hasSize(60);
    long nulls = points.stream().filter(p -> p.get("value") == null).count();
    assertThat(nulls).isGreaterThan(50);
    assertThat(points.stream().noneMatch(p -> Integer.valueOf(0).equals(p.get("value")))).isTrue();
  }

  @Test
  @DisplayName("AC-006 error budget exhausted → CRITICAL SLO_ERROR_BUDGET_EXHAUSTED")
  void ac006_errorBudgetExhausted() {
    // target 95, remaining ≤ 0 when current ≤ 90
    source.setOrderSlaPct30d(new BigDecimal("90.0"));
    Map<String, Object> slo = query.slo(superAdmin);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) slo.get("slos");
    Map<String, Object> orderSla =
        rows.stream()
            .filter(r -> "order_sla_adherence".equals(r.get("slo_name")))
            .findFirst()
            .orElseThrow();
    assertThat(((BigDecimal) orderSla.get("error_budget_remaining_pct")).compareTo(BigDecimal.ZERO))
        .isLessThanOrEqualTo(0);
    collection.collectAndEvaluate();
    assertThat(alerts.findOpen(AlertType.SLO_ERROR_BUDGET_EXHAUSTED, null)).isPresent();
    assertThat(alerts.findOpen(AlertType.SLO_ERROR_BUDGET_EXHAUSTED, null).orElseThrow().severity())
        .isEqualTo(AlertSeverity.CRITICAL);
  }

  @Test
  @DisplayName("AC-007 re-acknowledge → 409 ALREADY_ACKNOWLEDGED")
  void ac007_alreadyAcknowledged() {
    source.setGmvCurrentHourPaise(100_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    collection.collectAndEvaluate();
    UUID id = alerts.findOpen(AlertType.GMV_DROP, null).orElseThrow().id();
    query.acknowledge(superAdmin, id, "looking");
    assertThatThrownBy(() -> query.acknowledge(superAdmin, id, "again"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_ACKNOWLEDGED");
  }

  @Test
  @DisplayName("AC-008 sla_pct period_minutes=1440 → 1440 points")
  void ac008_dayOfPoints() {
    Map<String, Object> data = query.metrics(superAdmin, "sla_pct", 1440);
    @SuppressWarnings("unchecked")
    List<?> points = (List<?>) data.get("data_points");
    assertThat(points).hasSize(1440);
    assertThat(data.get("period_minutes")).isEqualTo(1440);
  }

  @Test
  void financeMetricRbacAndErrors() {
    assertThat(query.metrics(finance, "gmv", 60)).containsKey("data_points");
    assertThatThrownBy(() -> query.metrics(finance, "sla_pct", 60))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> query.metrics(superAdmin, "nope", 60))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_METRIC");
    assertThatThrownBy(() -> query.metrics(superAdmin, "gmv", 99))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(() -> query.realtime(finance))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThat(query.alerts(support, "ACTIVE", null, 1).data()).containsKey("alerts");
  }

  @Test
  void paymentPayoutSlaZoneAndDedup() {
    source.setPaymentAttempts15m(25);
    source.setPaymentSuccessRatePct15m(new BigDecimal("88.0"));
    collection.collectAndEvaluate();
    assertThat(alerts.findOpen(AlertType.PAYMENT_FAILURE, null).orElseThrow().severity())
        .isEqualTo(AlertSeverity.CRITICAL);

    source.setPaymentSuccessRatePct15m(new BigDecimal("99.0"));
    source.setPayoutVolumeLastHourPaise(400_000);
    source.setPayoutHourlyAvg7dPaise(100_000);
    source.setSlaAdherencePctLastHour(new BigDecimal("70.0"));
    UUID zone = UUID.fromString("33333333-3333-3333-3333-333333333333");
    source.setZones(
        List.of(
            new com.nammamedmate.observability_ops.application.port.out.MetricSourcePort
                .ZoneRiderSnapshot(zone, "DarkZone", 0, 4)));
    // Seed >30 consecutive zero buckets (fixed clock would otherwise upsert one bucket).
    Instant bucket = T0.truncatedTo(ChronoUnit.MINUTES);
    for (int i = 0; i < 32; i++) {
      samples.upsert(
          "rider_online_count", bucket.minus(i, ChronoUnit.MINUTES), BigDecimal.ZERO, zone);
    }
    collection.collectAndEvaluate();
    assertThat(alerts.findOpen(AlertType.PAYOUT_SPIKE, null)).isPresent();
    assertThat(alerts.findOpen(AlertType.SLA_BREACH_RATE, null)).isPresent();
    assertThat(alerts.findOpen(AlertType.ZONE_DARK, zone)).isPresent();
    long zoneDarkCount =
        alerts.findOpen().stream().filter(a -> a.type() == AlertType.ZONE_DARK).count();
    assertThat(zoneDarkCount).isEqualTo(1);
  }

  @Test
  void purgeAndAcknowledgeNotFound() {
    assertThatThrownBy(() -> query.acknowledge(superAdmin, UUID.randomUUID(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALERT_NOT_FOUND");
    source.setGmvCurrentHourPaise(100_000);
    source.setGmvSameHourDowAvgPaise(1_000_000);
    collection.collectAndEvaluate();
    assertThat(collection.purgeOlderThanDays(90)).isZero();
  }

  private static ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> emptyOutbox() {
    return new ObjectProvider<>() {
      @Override
      public com.nammamedmate.messaging.OutboxPublisher getObject() {
        return null;
      }
    };
  }
}
