package com.nammamedmate.observability_ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.adapter.out.persistence.StubMetricSourceAdapter;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Alerts;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Samples;
import com.nammamedmate.observability_ops.application.InMemoryMonitoringStores.Slos;
import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class MonitoringQueryServiceCoverageTest {

  @Test
  void coversUnavailableTrendAndRoles() {
    StubMetricSourceAdapter source = new StubMetricSourceAdapter();
    Samples samples = new Samples();
    Alerts alerts = new Alerts();
    Slos slos = new Slos();
    slos.putPrevious("order_sla_adherence", new BigDecimal("96.0"));
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);
    MonitoringQueryService query = new MonitoringQueryService(source, samples, alerts, slos, clock);
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

    Map<String, Object> realtime = query.realtime(ops);
    assertThat(realtime.get("data_age_seconds")).isEqualTo(Long.MAX_VALUE);

    Map<String, Object> slo = query.slo(ops);
    @SuppressWarnings("unchecked")
    var rows = (java.util.List<Map<String, Object>>) slo.get("slos");
    assertThat(rows.getFirst().get("trend")).isEqualTo("DEGRADING");

    query.alerts(ops, "bogus", "NOPE", 0);
    assertThatThrownBy(() -> query.slo(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> MonitoringQueryService.requireOps(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> MonitoringQueryService.requireAlertReader(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> MonitoringQueryService.requireMetricsAccess(null, "gmv"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MetricSampleStore failing = mock(MetricSampleStore.class);
    when(failing.latestBucketTs()).thenThrow(new QueryTimeoutException("down"));
    MonitoringQueryService bad = new MonitoringQueryService(source, failing, alerts, slos, clock);
    assertThatThrownBy(() -> bad.realtime(ops))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("METRICS_UNAVAILABLE");
    when(failing.series(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(new QueryTimeoutException("down"));
    assertThatThrownBy(() -> bad.metrics(ops, "gmv", 60))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("METRICS_UNAVAILABLE");

    // STABLE trend when previous exists but drop < 1pp
    slos.putPrevious("payment_success", new BigDecimal("99.4"));
    source.setPaymentSuccessPct30d(new BigDecimal("99.0"));
    @SuppressWarnings("unchecked")
    var stableRows = (java.util.List<Map<String, Object>>) query.slo(ops).get("slos");
    assertThat(
            stableRows.stream()
                .filter(r -> "payment_success".equals(r.get("slo_name")))
                .findFirst()
                .orElseThrow()
                .get("trend"))
        .isEqualTo("STABLE");

    query.alerts(ops, null, "  ", null);
    assertThatThrownBy(
            () ->
                MonitoringQueryService.requireMetricsAccess(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j"),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }
}
