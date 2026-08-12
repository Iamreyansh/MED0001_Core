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
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CoverageStory003Test {

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
  private MedmatePrincipal finance;
  private MedmatePrincipal support;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    incidents = new Incidents();
    numbers = new Numbers();
    alerts = new Alerts();
    slos = new Slos();
    metrics = new StubMetricSourceAdapter();
    notify = new OutboxNotificationDispatchAdapter(emptyOutbox());
    admins = new StubOnlineAdminDirectoryAdapter();
    service =
        new IncidentService(
            incidents,
            numbers,
            alerts,
            metrics,
            notify,
            admins,
            slos,
            Clock.fixed(T0, ZoneOffset.UTC));
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void autoCreateHighP2AndSkips() {
    UUID highId = UUID.randomUUID();
    UUID ackId = UUID.randomUUID();
    UUID existingId = UUID.randomUUID();
    UUID tooYoungHigh = UUID.randomUUID();
    UUID tooYoungCritical = UUID.randomUUID();
    UUID lowId = UUID.randomUUID();
    alerts.insert(
        openAlert(
            highId, AlertSeverity.HIGH, AlertType.ZONE_DARK, T0.minus(31, ChronoUnit.MINUTES)));
    alerts.insert(
        new MonitoringAlert(
            ackId,
            AlertSeverity.CRITICAL,
            AlertType.GMV_DROP,
            "acked",
            "gmv",
            BigDecimal.ONE,
            BigDecimal.TEN,
            null,
            T0.minus(20, ChronoUnit.MINUTES),
            true,
            ops.subject(),
            T0,
            "n",
            false,
            null,
            null));
    alerts.insert(
        openAlert(
            existingId,
            AlertSeverity.CRITICAL,
            AlertType.GMV_DROP,
            T0.minus(20, ChronoUnit.MINUTES)));
    incidents.insert(
        new Incident(
            UUID.randomUUID(),
            "INC-20260724-099",
            "linked",
            IncidentSeverity.P1,
            "d",
            IncidentStatus.DETECTED,
            List.of(),
            Map.of(),
            0L,
            null,
            null,
            null,
            false,
            null,
            null,
            T0,
            null,
            null,
            null,
            existingId,
            List.of()));
    alerts.insert(
        openAlert(
            tooYoungHigh,
            AlertSeverity.HIGH,
            AlertType.PAYMENT_FAILURE,
            T0.minus(5, ChronoUnit.MINUTES)));
    alerts.insert(
        openAlert(
            tooYoungCritical,
            AlertSeverity.CRITICAL,
            AlertType.GMV_DROP,
            T0.minus(5, ChronoUnit.MINUTES)));
    alerts.insert(
        openAlert(
            lowId,
            AlertSeverity.LOW,
            AlertType.DISPATCH_FAILURE,
            T0.minus(60, ChronoUnit.MINUTES)));
    service.runAutoCreate();
    assertThat(incidents.findBySourceAlertId(highId)).isPresent();
    assertThat(incidents.findBySourceAlertId(highId).orElseThrow().severity())
        .isEqualTo(IncidentSeverity.P2);
    assertThat(incidents.findBySourceAlertId(ackId)).isEmpty();
    assertThat(incidents.findBySourceAlertId(tooYoungHigh)).isEmpty();
    assertThat(incidents.findBySourceAlertId(tooYoungCritical)).isEmpty();
    assertThat(incidents.findBySourceAlertId(lowId)).isEmpty();
  }

  @Test
  void declareValidationAndRoles() {
    assertThatThrownBy(
            () -> service.declare(ops, " ", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(
            () -> service.declare(ops, null, "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(
            () -> service.declare(ops, "t", "P1", null, List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(
            () -> service.declare(ops, "t", "P1", "  ", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(
            () -> service.declare(ops, "t", "PX", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SEVERITY");
    assertThatThrownBy(
            () -> service.declare(ops, "t", null, "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SEVERITY");
    assertThatThrownBy(
            () -> service.declare(ops, "t", "  ", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SEVERITY");
    assertThatThrownBy(() -> service.declare(ops, "t", "P1", "d", null, Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SERVICE");
    assertThatThrownBy(() -> service.declare(ops, "t", "P1", "d", List.of(), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SERVICE");
    assertThatThrownBy(() -> service.declare(ops, "t", "P1", "d", List.of(" "), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SERVICE");
    assertThatThrownBy(
            () ->
                service.declare(
                    ops, "t", "P1", "d", java.util.Arrays.asList((String) null), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SERVICE");
    assertThatThrownBy(() -> service.declare(ops, "t", "P1", "d", List.of("NOPE"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SERVICE");
    assertThatThrownBy(
            () -> service.declare(finance, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> service.declare(null, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThat(service.declare(ops, "t", "P3", "d", List.of("CUSTOMER_APP"), null))
        .containsKey("id");
  }

  @Test
  void listFiltersReadersAndResolvedDto() {
    Map<String, Object> created =
        service.declare(ops, "t", "P2", "d", List.of("DISPATCH"), Map.of("k", 1));
    UUID id = UUID.fromString(created.get("id").toString());
    assertThat(service.list(ops, null, null, null, null, 0).data()).containsKey("incidents");
    assertThat(service.list(ops, "", "", "", "", null).data()).containsKey("incidents");
    assertThat(
            service
                .list(
                    ops,
                    "DETECTED",
                    "P2",
                    T0.minusSeconds(60).toString(),
                    T0.plusSeconds(60).toString(),
                    1)
                .data()
                .get("incidents"))
        .asList()
        .isNotEmpty();
    assertThatThrownBy(() -> service.list(ops, "NOPE", null, null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThatThrownBy(() -> service.list(ops, null, "PX", null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SEVERITY");
    assertThat(service.list(finance, null, null, null, null, 1).data()).containsKey("incidents");
    assertThat(service.list(support, null, null, null, null, 1).data()).containsKey("incidents");
    assertThatThrownBy(() -> service.list(customer, null, null, null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(null, null, null, null, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    service.resolve(ops, id, "root", "fix", "prevent");
    assertThatThrownBy(() -> service.resolve(ops, id, "root", "fix", "prevent"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INCIDENT_ALREADY_RESOLVED");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "INVESTIGATING", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INCIDENT_ALREADY_RESOLVED");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            service.list(ops, "RESOLVED", null, null, null, 1).data().get("incidents");
    assertThat(rows.getFirst().get("resolved_at")).isNotNull();
    assertThat(rows.getFirst().get("duration_minutes")).isNotNull();
  }

  @Test
  void patchResolvePostmortemAndSloSnapshot() {
    Map<String, Object> created =
        service.declare(ops, "t", "P1", "d", List.of("PAYMENT_GATEWAY"), Map.of());
    UUID id = UUID.fromString(created.get("id").toString());
    assertThatThrownBy(() -> service.patchStatus(ops, UUID.randomUUID(), "INVESTIGATING", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INCIDENT_NOT_FOUND");
    assertThatThrownBy(() -> service.patchStatus(ops, id, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "NOPE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThatThrownBy(() -> service.patchStatus(ops, id, "RESOLVED", "no"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
    assertThat(service.patchStatus(ops, id, "INVESTIGATING", null))
        .containsEntry("new_status", "INVESTIGATING");

    // resolvedAt set but durationMinutes null → list dto skip live duration
    incidents.insert(
        new Incident(
            UUID.randomUUID(),
            "INC-20260724-088",
            "odd",
            IncidentSeverity.P3,
            "d",
            IncidentStatus.RESOLVED,
            List.of(),
            Map.of(),
            0L,
            "r",
            "f",
            "p",
            false,
            null,
            null,
            T0.minusSeconds(120),
            T0,
            null,
            ops.subject(),
            null,
            List.of()));
    assertThat(service.list(ops, "RESOLVED", "P3", null, null, 1).data().get("incidents"))
        .asList()
        .isNotEmpty();

    Map<String, Object> p3 =
        service.declare(ops, "p3", "P3", "d", List.of("CUSTOMER_APP"), Map.of());
    UUID p3Id = UUID.fromString(p3.get("id").toString());
    Map<String, Object> resolvedP3 = service.resolve(ops, p3Id, "r", "f", "p");
    assertThat(resolvedP3.get("postmortem_required")).isEqualTo(false);
    assertThat(resolvedP3.get("postmortem_deadline")).isNull();

    assertThatThrownBy(() -> service.resolve(ops, UUID.randomUUID(), "r", "f", "p"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INCIDENT_NOT_FOUND");
    assertThatThrownBy(() -> service.resolve(ops, id, null, "f", "p"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(() -> service.resolve(ops, id, "r", " ", "p"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");
    assertThatThrownBy(() -> service.resolve(ops, id, "r", "f", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REQUIRED_FIELDS");

    service.resolve(ops, id, "root", "fix", "prevent");
    assertThat(service.filePostmortem(ops, id)).containsEntry("postmortem_filed", true);
    assertThatThrownBy(() -> service.filePostmortem(ops, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INCIDENT_NOT_FOUND");
    assertThatThrownBy(() -> service.filePostmortem(finance, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThat(service.sloHistory(ops, "payment_success", "2026-07-01", "2026-07-31"))
        .containsKey("history");
    assertThat(service.sloHistory(ops, "  ", null, "")).containsKey("history");
    assertThatThrownBy(() -> service.sloHistory(finance, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    SloStore withUnknown =
        new SloStore() {
          @Override
          public List<SloDefinition> allDefinitions() {
            return List.of(
                new SloDefinition("unknown", "x", BigDecimal.TEN, "x", 30),
                new SloDefinition("order_sla_adherence", "x", new BigDecimal("95"), "sla_pct", 30),
                new SloDefinition(
                    "payment_success", "x", new BigDecimal("99"), "payment_success_pct", 30),
                new SloDefinition(
                    "dispatch_success", "x", new BigDecimal("98"), "dispatch_rate", 30),
                new SloDefinition(
                    "api_p99_latency", "x", new BigDecimal("100"), "api_p99_compliance_pct", 30));
          }

          @Override
          public Optional<SloDefinition> byMetricName(String metricName) {
            return Optional.empty();
          }

          @Override
          public Optional<BigDecimal> previousActualPct(String sloName) {
            return Optional.empty();
          }

          @Override
          public void insertHistory(SloComplianceRecord record) {
            slos.insertHistory(record);
          }

          @Override
          public List<SloComplianceRecord> listHistory(
              String sloName, LocalDate periodFrom, LocalDate periodTo) {
            return slos.listHistory(sloName, periodFrom, periodTo);
          }
        };
    IncidentService snapSvc =
        new IncidentService(
            incidents,
            numbers,
            alerts,
            metrics,
            notify,
            admins,
            withUnknown,
            Clock.fixed(T0, ZoneOffset.UTC));
    snapSvc.runMonthlySloSnapshot();
    assertThat(slos.listHistory(null, null, null)).isNotEmpty();
  }

  @Test
  void incidentStatusTransitionFalseBranch() {
    assertThat(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.RESOLVED)).isFalse();
    assertThat(IncidentStatus.DETECTED.canTransitionTo(IncidentStatus.RESOLVED)).isFalse();
    assertThat(IncidentStatus.DETECTED.canTransitionTo(null)).isFalse();
  }

  private static MonitoringAlert openAlert(
      UUID id, AlertSeverity severity, AlertType type, Instant triggeredAt) {
    return new MonitoringAlert(
        id,
        severity,
        type,
        "m",
        "metric",
        BigDecimal.ONE,
        BigDecimal.TEN,
        null,
        triggeredAt,
        false,
        null,
        null,
        null,
        false,
        null,
        null);
  }

  private static ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> emptyOutbox() {
    @SuppressWarnings("unchecked")
    ObjectProvider<com.nammamedmate.messaging.OutboxPublisher> provider =
        org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }
}
