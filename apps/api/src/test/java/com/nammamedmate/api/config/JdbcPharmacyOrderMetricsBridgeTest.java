package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.OrderListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.Performance;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PeriodMetrics;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RecentOrder;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPharmacyOrderMetricsBridgeTest {

  @Test
  @SuppressWarnings("unchecked")
  void metricsPaths() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyOrderMetricsBridge bridge = new JdbcPharmacyOrderMetricsBridge(jdbc);
    UUID pharmacyId = UUID.randomUUID();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(10);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(50_000L);
    Performance perf = bridge.performance(pharmacyId);
    assertThat(perf.orders30d()).isEqualTo(10);
    assertThat(perf.gmv30dPaise()).isEqualTo(50_000L);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    Performance empty = bridge.performance(pharmacyId);
    assertThat(empty.orders30d()).isZero();
    assertThat(empty.gmv30dPaise()).isZero();
    assertThat(empty.cancelRatePct()).isEqualByComparingTo("0.00");

    assertThat(bridge.commissionLedger(pharmacyId).gmvCurrentPeriodPaise()).isZero();
    assertThat(bridge.listRatings(pharmacyId, null, null, null, 10, 0).total()).isZero();

    ResultSet rs = mock(ResultSet.class);
    UUID orderId = UUID.randomUUID();
    when(rs.getString("id")).thenReturn(orderId.toString());
    when(rs.getString("order_number")).thenReturn("ORD-1");
    when(rs.getString("status")).thenReturn("DELIVERED");
    when(rs.getLong("total_payable_paise")).thenReturn(1000L);
    when(rs.getTimestamp("created_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<RecentOrder> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(bridge.recentOrders(pharmacyId, 5)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(4);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(9000L);
    PeriodMetrics period = bridge.periodMetrics(pharmacyId, LocalDate.of(2026, 8, 8), 30);
    assertThat(period.ordersReceived()).isEqualTo(4);
    assertThat(period.gmvPeriodPaise()).isEqualTo(9000L);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    PeriodMetrics zeroPeriod = bridge.periodMetrics(pharmacyId, LocalDate.of(2026, 8, 8), 0);
    assertThat(zeroPeriod.ordersReceived()).isZero();
    assertThat(zeroPeriod.gmvPeriodPaise()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    ResultSet listRs = mock(ResultSet.class);
    when(listRs.getString("id")).thenReturn(orderId.toString());
    when(listRs.getString("order_number")).thenReturn("ORD-1");
    when(listRs.getString("status")).thenReturn("DELIVERED");
    when(listRs.getLong("total_payable_paise")).thenReturn(1000L);
    when(listRs.getString("items")).thenReturn("[{\"product_id\":\"" + UUID.randomUUID() + "\"}]");
    when(listRs.getObject("prescription_id")).thenReturn(UUID.randomUUID());
    when(listRs.getTimestamp("created_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
    when(listRs.getTimestamp("accepted_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:10:00Z")));
    when(listRs.getTimestamp("delivered_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:40:00Z")));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(listRs, 0));
            });

    OrderListResult listed =
        bridge.listOrders(
            pharmacyId, "DELIVERED", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8), 20, 0);
    assertThat(listed.total()).isEqualTo(1);
    assertThat(listed.orders()).hasSize(1);
    assertThat(listed.orders().getFirst().prepMinutes()).isEqualTo(30);
    assertThat(listed.orders().getFirst().hasRx()).isTrue();

    when(listRs.getString("items")).thenReturn(null);
    when(listRs.getObject("prescription_id")).thenReturn(null);
    when(listRs.getTimestamp("accepted_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-08-01T00:10:00Z")));
    when(listRs.getTimestamp("delivered_at")).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    OrderListResult nullItems = bridge.listOrders(pharmacyId, null, null, null, 0, -1);
    assertThat(nullItems.total()).isZero();
    assertThat(nullItems.orders().getFirst().prepMinutes()).isZero();

    when(listRs.getString("items")).thenReturn("   ");
    when(listRs.getTimestamp("accepted_at")).thenReturn(null);
    OrderListResult blankItems = bridge.listOrders(pharmacyId, "  ", null, null, 5, 0);
    assertThat(blankItems.orders().getFirst().itemCount()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(42L);
    assertThat(
            bridge.gmvForPeriodPaise(
                pharmacyId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 8)))
        .isEqualTo(42L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    assertThat(bridge.annualGmvYtdPaise(pharmacyId)).isZero();
  }
}
