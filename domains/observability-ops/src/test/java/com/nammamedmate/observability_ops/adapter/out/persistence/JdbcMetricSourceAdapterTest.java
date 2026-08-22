package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort.ZoneRiderSnapshot;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class JdbcMetricSourceAdapterTest {

  private JdbcTemplate jdbc;
  private JdbcMetricSourceAdapter adapter;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    adapter = new JdbcMetricSourceAdapter(jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void readsLiveTotalsAndPercents() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(100L, null, 20L);
    assertThat(adapter.gmvLastHourPaise()).isEqualTo(100L);
    assertThat(adapter.gmvCurrentHourPaise()).isZero();
    assertThat(adapter.gmvSameHourDowAvgPaise()).isEqualTo(20L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(30L);
    assertThat(adapter.ordersPerMinute()).isEqualTo(3.0);
    assertThat(adapter.paymentAttempts15m()).isEqualTo(30);
    assertThat(adapter.payoutVolumeLastHourPaise()).isEqualTo(30L);
    assertThat(adapter.payoutHourlyAvg7dPaise()).isEqualTo(30L);
    assertThat(adapter.activeAutomations()).isEqualTo(30);
    assertThat(adapter.pendingApprovals()).isEqualTo(30);
    assertThat(adapter.apiP99CompliancePct30d()).isEqualByComparingTo("0");

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<BigDecimal> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("ok")).thenReturn(9L);
              when(rs.getLong("total")).thenReturn(10L);
              return ex.extractData(rs);
            });
    assertThat(adapter.dispatchSuccessRatePct()).isEqualByComparingTo("90.0");
    assertThat(adapter.slaAdherencePctLastHour()).isEqualByComparingTo("90.0");
    assertThat(adapter.paymentSuccessRatePct15m()).isEqualByComparingTo("90.0");
    assertThat(adapter.orderSlaPct30d()).isEqualByComparingTo("90.0");
    assertThat(adapter.paymentSuccessPct30d()).isEqualByComparingTo("90.0");
    assertThat(adapter.dispatchSuccessPct30d()).isEqualByComparingTo("90.0");

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<BigDecimal> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(adapter.dispatchSuccessRatePct()).isEqualByComparingTo("100.0");

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<BigDecimal> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("ok")).thenReturn(0L);
              when(rs.getLong("total")).thenReturn(0L);
              return ex.extractData(rs);
            });
    assertThat(adapter.paymentSuccessRatePct15m()).isEqualByComparingTo("100.0");

    UUID zone = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<ZoneRiderSnapshot> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(zone);
              when(rs.getString("name")).thenReturn("Koramangala Zone");
              when(rs.getInt("online")).thenReturn(2);
              when(rs.getInt("demand")).thenReturn(3);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.zoneRiders()).hasSize(1);
  }
}
