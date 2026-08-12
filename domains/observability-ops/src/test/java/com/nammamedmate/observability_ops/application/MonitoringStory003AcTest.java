package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.adapter.out.messaging.OutboxNotificationDispatchAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubMetricSourceAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubOnlineAdminDirectoryAdapter;
import com.nammamedmate.observability_ops.application.InMemoryIncidentStores.Incidents;
import com.nammamedmate.observability_ops.application.InMemoryIncidentStores.Numbers;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Slos;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MonitoringStory003AcTest {

  private static final Instant T0 = Instant.parse("2026-07-24T10:00:00Z");

  private Incidents incidents;
  private Numbers numbers;
  private Alerts alerts;
  private Slos slos;
  private StubMetricSourceAdapter metrics;
  private OutboxNotificationDispatchAdapter notify;
  private StubOnlineAdminDirectoryAdapter admins;
  private IncidentService service;
  private MedmatePrincipal ops;
  private Clock clock;

  @BeforeEach
  void setUp() {
    incidents = new Incidents();
    numbers = new Numbers();
    alerts = new Alerts();
    slos = new Slos();
    metrics = new StubMetricSourceAdapter();
    notify = new OutboxNotificationDispatchAdapter(emptyOutbox());
    admins = new StubOnlineAdminDirectoryAdapter();
    clock = Clock.fixed(T0, ZoneOffset.UTC);
    service = new IncidentService(incidents, numbers, alerts, metrics, notify, admins, slos, clock);
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  @Test
  @DisplayName("AC-001 CRITICAL unacked 15m auto-creates P1 SYSTEM")
  void ac001_autoP1() {
    UUID alertId = UUID.randomUUID();
    alerts.insert(
        new MonitoringAlert(
            alertId,
            AlertSeverity.CRITICAL,
            AlertType.GMV_DROP,
            "gmv drop",
            "gmv",
            new BigDecimal("10"),
            new BigDecimal("50"),
            null,
            T0.minus(16, ChronoUnit.MINUTES),
            false,
            null,
            null,
            null,
            false,
            null,
            null));
    service.runAutoCreate();
    Incident created = incidents.findBySourceAlertId(alertId).orElseThrow();
    assertThat(created.severity()).isEqualTo(IncidentSeverity.P1);
    assertThat(created.createdBy()).isNull();
    assertThat(service.list(ops, null, null, null, null, 1).data().get("incidents"))
        .asList()
        .anySatisfy(
            row -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> m = (Map<String, Object>) row;
              assertThat(m.get("created_by")).isEqualTo("SYSTEM");
            });
  }

  @Test
  @DisplayName("AC-002 incident_number INC-YYYYMMDD-NNN")
  void ac002_incidentNumber() {
    Map<String, Object> created =
        service.declare(
            ops,
            "Payment gateway degraded",
            "P1",
            "capture rate dropped",
            List.of("PAYMENT_GATEWAY"),
            Map.of());
    assertThat(created.get("incident_number").toString()).matches("INC-\\d{8}-\\d{3}");
  }

  @Test
  @DisplayName("AC-003 invalid/backward status → 422 INVALID_STATUS_TRANSITION")
  void ac003_invalidTransition() {
    Map<String, Object> created =
        service.declare(ops, "t", "P2", "d", List.of("DISPATCH"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    service.patchStatus(ops, id, "INVESTIGATING", "looking");
    service.patchStatus(ops, id, "MITIGATING", "fixing");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "DETECTING", "bad"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "DETECTED", "back"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
  }

  @Test
  @DisplayName("AC-004 resolve P1 sets postmortem_required + deadline +48h")
  void ac004_postmortemDeadline() {
    Map<String, Object> created =
        service.declare(ops, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> resolved = service.resolve(ops, id, "root", "fix", "prevent");
    assertThat(resolved.get("postmortem_required")).isEqualTo(true);
    assertThat(resolved.get("postmortem_deadline"))
        .isEqualTo(T0.plus(48, ChronoUnit.HOURS).toString());
  }

  @Test
  @DisplayName("AC-005 empty root_cause → 400 MISSING_REQUIRED_FIELDS")
  void ac005_missingFields() {
    Map<String, Object> created =
        service.declare(ops, "t", "P3", "d", List.of("CUSTOMER_APP"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    assertThatThrownBy(() -> service.resolve(ops, id, " ", "fix", "prevent"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
  }

  @Test
  @DisplayName("AC-006 P1 declare → HIGH push outbox")
  void ac006_pageOnDeclare() {
    notify.clearDispatched();
    service.declare(ops, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of());
    assertThat(notify.dispatched()).hasSize(1);
    assertThat(notify.dispatched().getFirst().kind()).isEqualTo("incident");
    assertThat(notify.dispatched().getFirst().channels()).containsExactly("push");
    assertThat(notify.dispatched().getFirst().priority()).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("AC-007 slo/history consumed_pct negative = headroom")
  void ac007_sloHistory() {
    slos.insertHistory(
        new SloComplianceRecord(
            UUID.randomUUID(),
            "payment_success",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            new BigDecimal("99.00"),
            new BigDecimal("99.40"),
            true,
            new BigDecimal("-40.0"),
            1,
            T0));
    Map<String, Object> history = service.sloHistory(ops, "payment_success", null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) history.get("history");
    assertThat(rows).hasSize(1);
    assertThat(new BigDecimal(rows.getFirst().get("error_budget_consumed_pct").toString()))
        .isEqualByComparingTo("-40.0");
  }

  @Test
  @DisplayName("AC-008 status_history accumulates transitions")
  void ac008_statusHistory() {
    Map<String, Object> created =
        service.declare(ops, "t", "P2", "d", List.of("DISPATCH"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    service.patchStatus(ops, id, "INVESTIGATING", "looking");
    service.patchStatus(ops, id, "MITIGATING", "fixing");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            service.list(ops, null, null, null, null, 1).data().get("incidents");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history =
        (List<Map<String, Object>>) rows.getFirst().get("status_history");
    assertThat(history).hasSize(3);
    assertThat(history.get(0).get("status")).isEqualTo("DETECTED");
    assertThat(history.get(1).get("status")).isEqualTo("INVESTIGATING");
    assertThat(history.get(2).get("status")).isEqualTo("MITIGATING");
    assertThat(history.get(1).get("update_message")).isEqualTo("looking");
  }

  @Test
  @DisplayName("AC-009 +24h no postmortem → reminder to admin_super")
  void ac009_postmortemReminder() {
    Map<String, Object> created =
        service.declare(ops, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    Instant resolveAt = T0.minus(25, ChronoUnit.HOURS);
    Clock resolveClock = Clock.fixed(resolveAt, ZoneOffset.UTC);
    IncidentService resolveSvc =
        new IncidentService(
            incidents, numbers, alerts, metrics, notify, admins, slos, resolveClock);
    resolveSvc.resolve(ops, id, "root", "fix", "prevent");
    notify.clearDispatched();
    service.runPostmortemReminders();
    assertThat(notify.dispatched()).hasSize(1);
    assertThat(notify.dispatched().getFirst().kind()).isEqualTo("postmortem_reminder");
  }

  private static ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> emptyOutbox() {
    @SuppressWarnings("unchecked")
    ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> provider =
        org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }
}
