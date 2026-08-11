package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.HourlyDemandCell;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.LiveRiderCount;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.ZoneMetrics;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcPlatformGeographyStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final UUID zoneId = UUID.randomUUID();
  private final Instant from = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");
  private final LocalDate day = LocalDate.of(2026, 7, 24);

  @Test
  @SuppressWarnings("unchecked")
  void coversReadsAndRefresh() throws Exception {
    when(rs.getObject("zone_id")).thenReturn(zoneId);
    when(rs.getObject(1)).thenReturn(zoneId);
    when(rs.getString("zone_name")).thenReturn("Indiranagar");
    when(rs.getLong(anyString())).thenReturn(10L);
    when(rs.getInt(anyString())).thenReturn(2);
    when(rs.getInt("hour_of_day")).thenReturn(8);
    when(rs.getInt("day_of_week")).thenReturn(1);
    when(rs.getBigDecimal(anyString())).thenReturn(new BigDecimal("1.50"));
    when(rs.next()).thenReturn(true, false, true, false);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ((ResultSetExtractor<?>) inv.getArgument(1)).extractData(rs);
              return null;
            });
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ((ResultSetExtractor<?>) inv.getArgument(1)).extractData(rs);
              return null;
            });
    lenient().when(jdbc.update(anyString())).thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient().when(jdbc.update(anyString(), eq(Date.valueOf(day)))).thenReturn(1);
    lenient().when(jdbc.batchUpdate(anyString(), any(List.class))).thenReturn(new int[] {1});

    JdbcPlatformGeographyStore store = new JdbcPlatformGeographyStore(jdbc);
    assertThat(store.zoneExists(zoneId)).isTrue();

    List<ZoneMetrics> live = store.liveZoneMetrics(from, to);
    assertThat(live).hasSize(1);
    assertThat(live.getFirst().zoneName()).isEqualTo("Indiranagar");

    List<ZoneMetrics> agg = store.aggregatedZoneMetrics(day, day);
    assertThat(agg).hasSize(1);

    List<LiveRiderCount> riders = store.liveRidersOnlineByZone();
    assertThat(riders).hasSize(1);

    List<HourlyDemandCell> all = store.heatmapCells(null);
    assertThat(all).hasSize(1);
    List<HourlyDemandCell> one = store.heatmapCells(zoneId);
    assertThat(one).hasSize(1);

    store.refreshZoneDaily(day, day);
    store.refreshHourlyDemand(day.plusDays(1), 28);
    verify(jdbc).update(anyString(), eq(Date.valueOf(day)));
  }

  @Test
  void zoneMissingAndNullDecimals() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(rs.getObject("zone_id")).thenReturn(zoneId);
    when(rs.getString("zone_name")).thenReturn("Z");
    when(rs.getLong(anyString())).thenReturn(0L);
    when(rs.getInt(anyString())).thenReturn(0);
    when(rs.getBigDecimal(anyString())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));

    JdbcPlatformGeographyStore store = new JdbcPlatformGeographyStore(jdbc);
    assertThat(store.zoneExists(zoneId)).isFalse();
    ZoneMetrics m = store.aggregatedZoneMetrics(day, day).getFirst();
    assertThat(m.avgRidersOnline()).isEqualByComparingTo("0.00");
  }

  @Test
  void nullCountAndEmptyHourlyRefresh() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    when(jdbc.update(anyString())).thenReturn(1);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              when(rs.next()).thenReturn(false);
              ((ResultSetExtractor<?>) inv.getArgument(1)).extractData(rs);
              return null;
            });

    JdbcPlatformGeographyStore store = new JdbcPlatformGeographyStore(jdbc);
    assertThat(store.zoneExists(zoneId)).isFalse();
    store.refreshHourlyDemand(day.plusDays(1), 28);
  }
}
