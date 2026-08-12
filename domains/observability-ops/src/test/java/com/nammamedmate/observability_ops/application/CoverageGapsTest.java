package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.observability_ops.adapter.out.messaging.OutboxNotificationDispatchAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.JdbcMetricSampleStore;
import com.nammamedmate.observability_ops.adapter.out.persistence.JdbcMonitoringAlertStore;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubMetricSourceAdapter;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubOnlineAdminDirectoryAdapter;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Samples;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Slos;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort.ZoneRiderSnapshot;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.ErrorBudget;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CoverageGapsTest {

  @Test
  @SuppressWarnings("unchecked")
  void fillsRemainingBranches() throws Exception {
    assertThat(ErrorBudget.remainingPct(null, null)).isNotNull();
    assertThat(ErrorBudget.exhausted(null)).isFalse();

    StubMetricSourceAdapter stub = new StubMetricSourceAdapter();
    stub.setDispatchSuccessRatePct(new BigDecimal("98.0"));
    stub.setApiP99CompliancePct30d(new BigDecimal("99.0"));
    stub.setPaymentSuccessPct30d(new BigDecimal("99.0"));
    stub.setDispatchSuccessPct30d(new BigDecimal("98.0"));
    assertThat(stub.dispatchSuccessRatePct()).isEqualByComparingTo("98.0");

    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    OutboxNotificationDispatchAdapter notify = new OutboxNotificationDispatchAdapter(provider);
    notify.pageCritical(null, "X", null);
    assertThat(notify.dispatched()).hasSize(1);

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBigDecimal("value")).thenReturn(null);
              BigDecimal mapped = ((RowMapper<BigDecimal>) inv.getArgument(1)).mapRow(rs, 0);
              return java.util.Collections.singletonList(mapped);
            });
    JdbcMetricSampleStore samplesJdbc = new JdbcMetricSampleStore(jdbc);
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    samplesJdbc.upsert("gmv", ts, BigDecimal.ONE, null);
    samplesJdbc.upsert("rider_online_count", ts, BigDecimal.ZERO, UUID.randomUUID());
    assertThat(samplesJdbc.latestBucketTs()).isEmpty();
    assertThat(samplesJdbc.consecutiveZeroBuckets("rider_online_count", UUID.randomUUID(), ts, 5))
        .isZero();

    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    Instant at = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("severity")).thenReturn("HIGH");
    when(rs.getString("type")).thenReturn("ZONE_DARK");
    when(rs.getString("message")).thenReturn("m");
    when(rs.getString("triggering_metric")).thenReturn("rider_online_count");
    when(rs.getBigDecimal("triggering_value")).thenReturn(BigDecimal.ZERO);
    when(rs.getBigDecimal("threshold_value")).thenReturn(BigDecimal.ONE);
    when(rs.getObject("zone_id")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(at));
    when(rs.getBoolean("acknowledged")).thenReturn(true);
    when(rs.getObject("acknowledged_by")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp("acknowledged_at")).thenReturn(Timestamp.from(at));
    when(rs.getString("acknowledged_notes")).thenReturn("n");
    when(rs.getBoolean("auto_remediated")).thenReturn(true);
    when(rs.getTimestamp("resolved_at")).thenReturn(Timestamp.from(at));
    when(rs.getString("resolution_reason")).thenReturn("AUTO_RESOLVED");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    JdbcMonitoringAlertStore alertJdbc = new JdbcMonitoringAlertStore(jdbc);
    alertJdbc.insert(
        new MonitoringAlert(
            id,
            AlertSeverity.HIGH,
            AlertType.ZONE_DARK,
            "m",
            "rider_online_count",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            UUID.randomUUID(),
            at,
            true,
            UUID.randomUUID(),
            at,
            "n",
            true,
            at,
            "MANUAL_RESOLVED"));
    assertThat(alertJdbc.findById(id)).isPresent();
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
        .thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(alertJdbc.list(AlertListStatus.ACTIVE, AlertSeverity.HIGH, 1, 20).total())
        .isEqualTo(1);

    Samples samples = new Samples();
    Alerts alerts = new Alerts();
    Slos slos = new Slos();
    Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    StubOnlineAdminDirectoryAdapter admins = new StubOnlineAdminDirectoryAdapter();
    MetricCollectionService collection =
        new MetricCollectionService(stub, samples, alerts, slos, notify, admins, clock);
    stub.setGmvSameHourDowAvgPaise(0);
    stub.setPayoutHourlyAvg7dPaise(0);
    stub.setPaymentAttempts15m(5);
    stub.setSlaAdherencePctLastHour(null);
    SloStore emptySlo =
        new SloStore() {
          @Override
          public List<SloDefinition> allDefinitions() {
            return List.of(
                new SloDefinition("unknown", "x", BigDecimal.TEN, "x", 30),
                new SloDefinition("order_sla_adherence", "x", new BigDecimal("95"), "sla_pct", 30));
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
          public void insertHistory(SloComplianceRecord record) {}

          @Override
          public List<SloComplianceRecord> listHistory(
              String sloName, LocalDate periodFrom, LocalDate periodTo) {
            return List.of();
          }
        };
    MetricCollectionService collection2 =
        new MetricCollectionService(stub, samples, alerts, emptySlo, notify, admins, clock);
    collection2.collectAndEvaluate();
    stub.setPaymentAttempts15m(25);
    stub.setPaymentSuccessRatePct15m(new BigDecimal("92.0"));
    stub.setGmvSameHourDowAvgPaise(1_000_000);
    stub.setGmvCurrentHourPaise(800_000);
    stub.setPayoutHourlyAvg7dPaise(10_000);
    stub.setPayoutVolumeLastHourPaise(5_000);
    stub.setSlaAdherencePctLastHour(new BigDecimal("90"));
    stub.setZones(
        List.of(
            new ZoneRiderSnapshot(UUID.randomUUID(), "A", 2, 5),
            new ZoneRiderSnapshot(UUID.randomUUID(), "B", 0, 3),
            new ZoneRiderSnapshot(UUID.randomUUID(), "C", 8, 3)));
    collection.collectAndEvaluate();
    assertThat(alerts.findOpen(AlertType.PAYMENT_FAILURE, null).orElseThrow().severity())
        .isEqualTo(AlertSeverity.HIGH);
    // Dedup path: second fire updates triggered_at
    collection.collectAndEvaluate();

    // payment rate null early-return
    stub.setPaymentSuccessRatePct15m(null);
    stub.setPaymentAttempts15m(25);
    collection.collectAndEvaluate();

    MonitoringQueryService query = new MonitoringQueryService(stub, samples, alerts, slos, clock);
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    UUID alertId = alerts.findOpen(AlertType.PAYMENT_FAILURE, null).orElseThrow().id();
    query.acknowledge(ops, alertId, "notes");
    assertThat(query.alerts(ops, "ACKNOWLEDGED", "HIGH", 1).data().get("alerts"))
        .asList()
        .isNotEmpty();
    query.realtime(ops);
    query.metrics(ops, "gmv", null);
    query.slo(ops);
    assertThatThrownBy(() -> query.alerts(finance, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                MonitoringQueryService.requireMetricsAccess(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j"),
                    "gmv"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MonitoringQueryService queryUnknown =
        new MonitoringQueryService(stub, samples, alerts, emptySlo, clock);
    queryUnknown.slo(ops);

    assertThatThrownBy(() -> query.metrics(ops, null, 60))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_METRIC");

    // toAlertRow non-null ack/resolved fields + isOpen false
    UUID resolvedId = UUID.randomUUID();
    Instant now = at;
    alerts.insert(
        new MonitoringAlert(
            resolvedId,
            AlertSeverity.LOW,
            AlertType.DISPATCH_FAILURE,
            "done",
            "dispatch_rate",
            BigDecimal.ONE,
            BigDecimal.TEN,
            null,
            now,
            true,
            UUID.randomUUID(),
            now,
            "notes",
            false,
            now,
            "MANUAL_RESOLVED"));
    assertThat(alerts.findById(resolvedId).orElseThrow().isOpen()).isFalse();
    assertThat(query.alerts(ops, "RESOLVED", "LOW", 1).data().get("alerts")).asList().isNotEmpty();

    MedmatePrincipal support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThat(query.alerts(support, "ACTIVE", null, 1).data()).containsKey("alerts");
  }
}
