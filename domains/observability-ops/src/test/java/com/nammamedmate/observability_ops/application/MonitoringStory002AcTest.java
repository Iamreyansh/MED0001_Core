package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.adapter.out.messaging.OutboxNotificationDispatchAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.InMemoryPlaybookAuditAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubApiErrorRateAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubOnlineAdminDirectoryAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubPaymentJobRetryAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubPharmacyThrottleAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubRiderNotifyAdapter;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryRemediationStores.Logs;
import com.nammamedmate.observability_ops.application.InMemoryRemediationStores.Playbooks;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationTriggerType;
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

class MonitoringStory002AcTest {

  private static final Instant T0 = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ZONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PHARMACY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYBOOK_ZONE = UUID.fromString("02000002-0001-4000-8000-000000000001");

  private Playbooks playbooks;
  private Logs logs;
  private Alerts alerts;
  private StubRiderNotifyAdapter riders;
  private StubPharmacyThrottleAdapter pharmacies;
  private StubPaymentJobRetryAdapter payments;
  private StubApiErrorRateAdapter apiErrors;
  private InMemoryPlaybookAuditAdapter audit;
  private OutboxNotificationDispatchAdapter notify;
  private StubOnlineAdminDirectoryAdapter admins;
  private RemediationService remediation;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal ops;

  @BeforeEach
  void setUp() {
    playbooks = new Playbooks();
    logs = new Logs();
    alerts = new Alerts();
    riders = new StubRiderNotifyAdapter(emptyOutbox());
    pharmacies = new StubPharmacyThrottleAdapter();
    payments = new StubPaymentJobRetryAdapter();
    apiErrors = new StubApiErrorRateAdapter();
    audit = new InMemoryPlaybookAuditAdapter();
    notify = new OutboxNotificationDispatchAdapter(emptyOutbox());
    admins = new StubOnlineAdminDirectoryAdapter();
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    remediation =
        new RemediationService(
            playbooks,
            logs,
            alerts,
            riders,
            pharmacies,
            payments,
            apiErrors,
            audit,
            notify,
            admins,
            clock);
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  @Test
  @DisplayName("AC-001 ZONE_DARK auto REQUEST_RIDERS when playbook enabled")
  void ac001_zoneDarkAuto() {
    alerts.insert(zoneDarkAlert());
    remediation.runAutoCycle();
    List<RemediationLogEntry> entries = logs.all();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().triggerType()).isEqualTo(RemediationTriggerType.AUTO);
    assertThat(entries.getFirst().actionType()).isEqualTo(RemediationActionType.REQUEST_RIDERS);
    assertThat(entries.getFirst().status().name()).isEqualTo("SUCCESS");
    assertThat(riders.notified()).hasSize(1);
    assertThat(alerts.findById(entries.getFirst().alertId()).orElseThrow().autoRemediated())
        .isTrue();
  }

  @Test
  @DisplayName("AC-002 THROTTLE_PHARMACY reduces cap by 30% floor")
  void ac002_throttle() {
    pharmacies.put(PHARMACY, "Medplus - HSR Layout", new BigDecimal("60"), 3, 0, 20, false);
    remediation.runAutoCycle();
    RemediationLogEntry entry =
        logs.all().stream()
            .filter(e -> e.actionType() == RemediationActionType.THROTTLE_PHARMACY)
            .findFirst()
            .orElseThrow();
    assertThat(entry.actionDetails().get("previous_order_cap")).isEqualTo(20);
    assertThat(entry.actionDetails().get("new_order_cap")).isEqualTo(14);
  }

  @Test
  @DisplayName("AC-003 payment retry after delay; CRITICAL after 3 failed retries")
  void ac003_paymentRetries() {
    UUID jobId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    payments.putFailed(jobId, T0.minus(6, ChronoUnit.MINUTES), 0);
    remediation.runAutoCycle();
    assertThat(payments.failedRetryCount(jobId)).isEqualTo(1);
    remediation.runAutoCycle();
    assertThat(payments.failedRetryCount(jobId)).isEqualTo(2);
    remediation.runAutoCycle();
    assertThat(payments.failedRetryCount(jobId)).isEqualTo(3);
    MonitoringAlert critical = alerts.findOpen(AlertType.PAYMENT_JOB_FAILURE, jobId).orElseThrow();
    assertThat(critical.severity()).isEqualTo(AlertSeverity.CRITICAL);
    assertThat(notify.dispatched()).isNotEmpty();
    int before = logs.all().size();
    remediation.runAutoCycle();
    assertThat(logs.all()).hasSize(before);
  }

  @Test
  @DisplayName("AC-004 disabled playbook does not fire")
  void ac004_disablePlaybook() {
    remediation.patchPlaybook(superAdmin, PLAYBOOK_ZONE, false, null);
    alerts.insert(zoneDarkAlert());
    remediation.runAutoCycle();
    assertThat(logs.all()).isEmpty();
    assertThat(riders.notified()).isEmpty();
  }

  @Test
  @DisplayName("AC-005 manual REQUEST_RIDERS notifies offline riders")
  void ac005_manualRequestRiders() {
    Map<String, Object> res =
        remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", ZONE, "peak hours");
    assertThat(res.get("status")).isEqualTo("INITIATED");
    assertThat(riders.notified()).hasSize(1);
    assertThat(logs.all().getFirst().triggerType()).isEqualTo(RemediationTriggerType.MANUAL);
  }

  @Test
  @DisplayName("AC-006 manual rate limit 429 within 5 minutes")
  void ac006_rateLimited() {
    remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", ZONE, "first");
    assertThatThrownBy(
            () -> remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", ZONE, "second"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  @DisplayName("AC-007 list shows AUTO and MANUAL trigger_type")
  void ac007_listTriggerTypes() {
    alerts.insert(zoneDarkAlert());
    remediation.runAutoCycle();
    UUID otherZone = UUID.fromString("44444444-4444-4444-8444-444444444444");
    riders.putZone(otherZone, "Koramangala", 3);
    remediation.triggerManual(ops, "REQUEST_RIDERS", "ZONE", otherZone, "manual");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            remediation
                .listActions(ops, null, null, null, null, 1)
                .data()
                .get("remediation_actions");
    assertThat(rows).extracting(m -> m.get("trigger_type")).contains("AUTO", "MANUAL");
  }

  @Test
  @DisplayName("AC-008 playbook threshold patch audited with updated_by + diff")
  void ac008_playbookAudit() {
    Map<String, Object> data =
        remediation.patchPlaybook(
            superAdmin, PLAYBOOK_ZONE, null, Map.of("dark_duration_minutes", 45));
    assertThat(data.get("updated_by")).isEqualTo(superAdmin.subject());
    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().getFirst().updatedBy()).isEqualTo(superAdmin.subject());
    @SuppressWarnings("unchecked")
    Map<String, Object> beforeThreshold =
        (Map<String, Object>) audit.entries().getFirst().before().get("threshold");
    assertThat(beforeThreshold.get("dark_duration_minutes")).isEqualTo(30);
    @SuppressWarnings("unchecked")
    Map<String, Object> afterThreshold =
        (Map<String, Object>) audit.entries().getFirst().after().get("threshold");
    assertThat(afterThreshold.get("dark_duration_minutes")).isEqualTo(45);
  }

  private MonitoringAlert zoneDarkAlert() {
    return new MonitoringAlert(
        UUID.randomUUID(),
        AlertSeverity.HIGH,
        AlertType.ZONE_DARK,
        "Zone dark",
        "rider_online_count",
        BigDecimal.ZERO,
        BigDecimal.ONE,
        ZONE,
        T0,
        false,
        null,
        null,
        null,
        false,
        null,
        null);
  }

  private static ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> emptyOutbox() {
    return new ObjectProvider<>() {
      @Override
      public com.nammamedmate.messaging.OutboxPublisher getObject() {
        return null;
      }

      @Override
      public com.nammamedmate.messaging.OutboxPublisher getIfAvailable() {
        return null;
      }

      @Override
      public com.nammamedmate.messaging.OutboxPublisher getIfUnique() {
        return null;
      }
    };
  }
}
