package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.CategoryMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.GmvTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.KpiTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PharmacyLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.RiderLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.ZoneSalesRow;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
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
class JdbcPlatformOverviewStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final Instant from = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");
  private final LocalDate day = LocalDate.of(2026, 7, 24);

  @Test
  @SuppressWarnings("unchecked")
  void coversLiveAggregatedAndRefresh() throws Exception {
    UUID zoneId = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    UUID riderId = UUID.randomUUID();

    when(rs.getLong(anyString())).thenReturn(100L);
    when(rs.getDouble(anyString())).thenReturn(4.5);
    when(rs.getString(anyString())).thenReturn("UPI");
    when(rs.getString("category")).thenReturn("OTC_MEDICINES");
    when(rs.getString("method")).thenReturn("UPI");
    when(rs.getString("zone_name")).thenReturn("Indiranagar");
    when(rs.getString("name")).thenReturn("Apollo");
    when(rs.getString("area")).thenReturn("Indiranagar");
    when(rs.getString("zone")).thenReturn("Indiranagar");
    when(rs.getDate("d")).thenReturn(Date.valueOf(day));
    when(rs.getObject("zone_id")).thenReturn(zoneId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getObject("rider_id")).thenReturn(riderId);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    lenient().when(jdbc.update(anyString(), eq(Date.valueOf(day)))).thenReturn(1);
    lenient()
        .when(
            jdbc.update(
                anyString(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
        .thenReturn(1);
    lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

    JdbcPlatformOverviewStore store = new JdbcPlatformOverviewStore(jdbc);

    KpiTotals live = store.liveKpis(from, to);
    assertThat(live.gmvPaise()).isEqualTo(100L);
    assertThat(store.aggregatedKpis(day, day).ordersCount()).isEqualTo(100L);

    List<GmvTrendPoint> trend = store.liveGmvTrend(from, to);
    assertThat(trend.getFirst().date()).isEqualTo(day);
    assertThat(store.aggregatedGmvTrend(day, day)).isNotEmpty();

    List<CategoryMixRow> cats = store.liveCategoryMix(from, to);
    assertThat(cats.getFirst().category()).isEqualTo("OTC_MEDICINES");
    assertThat(store.aggregatedCategoryMix(day, day)).isNotEmpty();

    List<PaymentMixRow> pays = store.livePaymentMix(from, to);
    assertThat(pays.getFirst().method()).isEqualTo("UPI");
    assertThat(store.aggregatedPaymentMix(day, day)).isNotEmpty();

    List<ZoneSalesRow> zones = store.liveSalesByZone(from, to);
    assertThat(zones.getFirst().zoneId()).isEqualTo(zoneId);
    assertThat(store.aggregatedSalesByZone(day, day)).isNotEmpty();

    List<PharmacyLeader> ph = store.topPharmacies(from, to, 5);
    assertThat(ph.getFirst().pharmacyId()).isEqualTo(pharmacyId);
    List<RiderLeader> riders = store.topRiders(from, to, 5);
    assertThat(riders.getFirst().riderId()).isEqualTo(riderId);

    store.refreshDailySnapshots(day, day);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyResultsAndNullZone() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(null);
    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(List.of());

    JdbcPlatformOverviewStore store = new JdbcPlatformOverviewStore(jdbc);
    assertThat(store.liveKpis(from, to).gmvPaise()).isZero();
    assertThat(store.aggregatedKpis(day, day).gmvPaise()).isZero();

    when(rs.getObject("zone_id")).thenReturn(null);
    when(rs.getString("zone_name")).thenReturn("Unknown");
    when(rs.getLong(anyString())).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.update(anyString(), eq(Date.valueOf(day)))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);

    // refresh with null zone id row skipped for zone upsert
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> mapper = inv.getArgument(1);
              if (sql.contains("zone_id")) {
                when(rs.getObject("zone_id")).thenReturn(null);
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("payment_method") || sql.contains("analytics_payment")) {
                when(rs.getString("method")).thenReturn("COD");
                when(rs.getLong("orders")).thenReturn(2L);
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("category")) {
                when(rs.getString("category")).thenReturn("OTC_MEDICINES");
                when(rs.getLong("gmv")).thenReturn(9L);
                return List.of(mapper.mapRow(rs, 0));
              }
              when(rs.getLong(anyString())).thenReturn(0L);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenAnswer(
            inv -> {
              when(rs.getLong(anyString())).thenReturn(0L);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });

    store.refreshDailySnapshots(day, day);
    assertThat(store.liveSalesByZone(from, to).getFirst().zoneId()).isNull();
  }
}
