package com.nammamedmate.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartGranularity;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartPoint;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.PeriodTotals;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcFinanceOverviewStoreTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ResultSet rs;

  private final Instant from = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void kpiMapsRow() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<KpiSnapshot> mapper = inv.getArgument(1);
              when(rs.getLong("gmv_today")).thenReturn(100L);
              when(rs.getLong("platform_revenue")).thenReturn(10L);
              when(rs.getLong("pharmacy_due")).thenReturn(20L);
              when(rs.getLong("rider_due")).thenReturn(30L);
              when(rs.getLong("refunds_pending")).thenReturn(2L);
              when(rs.getLong("refunds_pending_value")).thenReturn(40L);
              when(rs.getLong("cod_in_hand")).thenReturn(50L);
              when(rs.getLong("wallet_total")).thenReturn(60L);
              when(rs.getLong("gateway_fees")).thenReturn(5L);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcFinanceOverviewStore store = new JdbcFinanceOverviewStore(jdbc);
    KpiSnapshot snap = store.kpi(from, to);
    assertThat(snap.gmvTodayPaise()).isEqualTo(100L);
    assertThat(snap.gatewayFeesTodayPaise()).isEqualTo(5L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void periodTotalsAndChartAndGmvSum() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              if (sql.contains("COMMISSION")) {
                when(rs.getLong("gmv")).thenReturn(1000L);
                when(rs.getLong("commission")).thenReturn(80L);
                when(rs.getLong("refunds")).thenReturn(10L);
                when(rs.getLong("gateway_fees")).thenReturn(5L);
                when(rs.getLong("pharmacy_payout")).thenReturn(700L);
                when(rs.getLong("rider_payout")).thenReturn(50L);
                when(rs.getLong("tcs")).thenReturn(10L);
                when(rs.getLong("orders")).thenReturn(3L);
                return List.of(mapper.mapRow(rs, 0));
              }
              when(rs.getString("label")).thenReturn("2026-07-24");
              when(rs.getLong("gmv")).thenReturn(1000L);
              when(rs.getLong("orders")).thenReturn(3L);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L, 3L, 1000L);

    JdbcFinanceOverviewStore store = new JdbcFinanceOverviewStore(jdbc);
    PeriodTotals totals = store.periodTotals(from, to);
    assertThat(totals.gmvPaise()).isEqualTo(1000L);
    assertThat(totals.codOrdersCount()).isEqualTo(1L);

    List<ChartPoint> daily = store.gmvChart(from, to, ChartGranularity.DAILY);
    assertThat(daily).hasSize(1);
    List<ChartPoint> hourly = store.gmvChart(from, to, ChartGranularity.HOURLY);
    assertThat(hourly).hasSize(1);
    assertThat(store.gmvSum(from, to)).isEqualTo(1000L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullAndEmptyQueryResults() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any()))
        .thenReturn(null)
        .thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(null)
        .thenReturn(List.of())
        .thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);

    JdbcFinanceOverviewStore store = new JdbcFinanceOverviewStore(jdbc);
    assertThat(store.kpi(from, to).gmvTodayPaise()).isZero();
    assertThat(store.kpi(from, to).gmvTodayPaise()).isZero();
    assertThat(store.periodTotals(from, to).gmvPaise()).isZero();
    assertThat(store.periodTotals(from, to).gmvPaise()).isZero();
    assertThat(store.gmvChart(from, to, ChartGranularity.DAILY)).isEmpty();
    assertThat(store.gmvSum(from, to)).isZero();
  }
}
