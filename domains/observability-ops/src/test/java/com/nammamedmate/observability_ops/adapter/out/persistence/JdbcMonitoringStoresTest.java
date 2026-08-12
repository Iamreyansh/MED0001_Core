package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcMonitoringStoresTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void metricSampleStoreCoversPaths() throws Exception {
    UUID id = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("metric_name")).thenReturn("gmv");
    when(rs.getTimestamp("bucket_at")).thenReturn(Timestamp.from(ts));
    when(rs.getBigDecimal("value")).thenReturn(BigDecimal.TEN);
    when(rs.getObject("zone_id")).thenReturn(null);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              when(rs.getBigDecimal("value")).thenReturn(BigDecimal.ZERO, null, BigDecimal.ONE);
              RowMapper<BigDecimal> mapper = inv.getArgument(1);
              java.util.ArrayList<BigDecimal> out = new java.util.ArrayList<>();
              out.add(mapper.mapRow(rs, 0));
              out.add(mapper.mapRow(rs, 1));
              out.add(mapper.mapRow(rs, 2));
              return out;
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));

    JdbcMetricSampleStore store = new JdbcMetricSampleStore(jdbc);
    store.upsert("gmv", ts, BigDecimal.TEN, null);
    store.upsert("rider_online_count", ts, BigDecimal.ZERO, zone);
    assertThat(store.latestBucketTs()).isPresent();
    assertThat(store.series("gmv", ts, ts.plusSeconds(60))).hasSize(1);
    assertThat(store.consecutiveZeroBuckets("rider_online_count", zone, ts, 10)).isEqualTo(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              when(rs.getBigDecimal("value")).thenReturn(BigDecimal.ONE);
              return List.of(((RowMapper<BigDecimal>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.consecutiveZeroBuckets("rider_online_count", zone, ts, 10)).isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenReturn(List.of());
    assertThat(store.consecutiveZeroBuckets("rider_online_count", zone, ts, 10)).isZero();
    assertThat(store.lastN("gmv", null, 5)).hasSize(1);
    when(rs.getObject("zone_id")).thenReturn(zone);
    assertThat(store.lastN("rider_online_count", zone, 5)).hasSize(1);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(List.of())
        .thenReturn(java.util.Collections.singletonList(null));
    assertThat(store.latestBucketTs()).isEmpty();
    assertThat(store.latestBucketTs()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void alertAndSloStoresCoverPaths() throws Exception {
    UUID id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("severity")).thenReturn("CRITICAL");
    when(rs.getString("type")).thenReturn("GMV_DROP");
    when(rs.getString("message")).thenReturn("m");
    when(rs.getString("triggering_metric")).thenReturn("gmv");
    when(rs.getBigDecimal("triggering_value")).thenReturn(BigDecimal.ONE);
    when(rs.getBigDecimal("threshold_value")).thenReturn(BigDecimal.TEN);
    when(rs.getObject("zone_id")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(ts));
    when(rs.getBoolean("acknowledged")).thenReturn(false);
    when(rs.getObject("acknowledged_by")).thenReturn(null);
    when(rs.getTimestamp("acknowledged_at")).thenReturn(null);
    when(rs.getString("acknowledged_notes")).thenReturn(null);
    when(rs.getBoolean("auto_remediated")).thenReturn(false);
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(rs.getString("resolution_reason")).thenReturn(null);
    when(rs.getString("slo_name")).thenReturn("payment_success");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getBigDecimal("target_pct")).thenReturn(new BigDecimal("99"));
    when(rs.getString("metric_name")).thenReturn("payment_success_pct");
    when(rs.getInt("measurement_window_days")).thenReturn(30);
    when(rs.getBigDecimal("actual_pct")).thenReturn(new BigDecimal("99.4"));

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

    JdbcMonitoringAlertStore alerts = new JdbcMonitoringAlertStore(jdbc);
    assertThat(alerts.findById(id)).isPresent();
    assertThat(alerts.findOpen(AlertType.GMV_DROP, null)).isPresent();
    assertThat(alerts.findOpen(AlertType.ZONE_DARK, UUID.randomUUID())).isPresent();
    assertThat(alerts.findOpen()).hasSize(1);
    MonitoringAlert inserted =
        alerts.insert(
            new MonitoringAlert(
                id,
                AlertSeverity.HIGH,
                AlertType.PAYOUT_SPIKE,
                "m",
                "payout_volume",
                BigDecimal.ONE,
                BigDecimal.TEN,
                null,
                ts,
                false,
                null,
                null,
                null,
                false,
                null,
                null));
    assertThat(inserted.id()).isEqualTo(id);
    alerts.updateTriggeredAt(id, ts);
    alerts.acknowledge(id, UUID.randomUUID(), ts, "n");
    alerts.resolve(id, ts, "AUTO_RESOLVED");
    alerts.markAutoRemediated(id, true);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(1L)
        .thenReturn(null);
    assertThat(alerts.list(AlertListStatus.ACTIVE, AlertSeverity.CRITICAL, 1, 20).total())
        .isEqualTo(1);
    assertThat(alerts.list(AlertListStatus.ACKNOWLEDGED, null, 1, 20).alerts()).hasSize(1);
    assertThat(alerts.list(AlertListStatus.RESOLVED, null, 1, 20).total()).isEqualTo(0L);
    assertThat(alerts.purgeOlderThan(ts)).isEqualTo(1);

    JdbcSloStore slo = new JdbcSloStore(jdbc);
    List<SloDefinition> defs = slo.allDefinitions();
    assertThat(defs).hasSize(1);
    assertThat(slo.byMetricName("payment_success_pct")).isPresent();
    assertThat(slo.previousActualPct("payment_success")).isPresent();
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .queryForObject(anyString(), eq(Long.class), any(Object[].class));
  }
}
