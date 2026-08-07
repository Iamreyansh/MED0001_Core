package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPerformanceAlertStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyPerformanceSnapshotStore;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore.AlertRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore.SnapshotRow;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyPerformanceJdbcCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
  private static final UUID PID = Ids.newId();

  @Test
  void jdbcPerformanceStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyPerformanceSnapshotStore snapshots = new JdbcPharmacyPerformanceSnapshotStore(jdbc);
    JdbcPerformanceAlertStore alerts = new JdbcPerformanceAlertStore(jdbc);

    SnapshotRow row =
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            10,
            9,
            1,
            new BigDecimal("90.00"),
            new BigDecimal("85.00"),
            new BigDecimal("5.00"),
            new BigDecimal("2.00"),
            new BigDecimal("14.0"),
            0,
            new BigDecimal("4.50"),
            20,
            100_000L,
            (short) 0,
            "STABLE",
            "STABLE",
            NOW);
    snapshots.upsert(row, NOW);
    alerts.insert(
        new AlertRow(
            Ids.newId(),
            PID,
            "LOW_FILL_RATE",
            Ids.newId(),
            new BigDecimal("78.50"),
            "msg",
            List.of("WHATSAPP", "IN_APP"),
            NOW));

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(row.id());
              when(rs.getObject("pharmacy_id")).thenReturn(PID);
              when(rs.getString("period")).thenReturn("30D");
              when(rs.getObject("period_start", LocalDate.class))
                  .thenReturn(LocalDate.parse("2026-06-24"));
              when(rs.getObject("period_end", LocalDate.class))
                  .thenReturn(LocalDate.parse("2026-07-23"));
              when(rs.getInt("orders_received")).thenReturn(10);
              when(rs.getInt("orders_fulfilled")).thenReturn(9);
              when(rs.getInt("orders_cancelled")).thenReturn(1);
              when(rs.getBigDecimal("fill_rate_pct")).thenReturn(new BigDecimal("90.00"));
              when(rs.getBigDecimal("on_time_prep_pct")).thenReturn(new BigDecimal("85.00"));
              when(rs.getBigDecimal("cancel_rate_pct")).thenReturn(new BigDecimal("5.00"));
              when(rs.getBigDecimal("out_of_stock_rate_pct")).thenReturn(new BigDecimal("2.00"));
              when(rs.getBigDecimal("avg_prep_minutes")).thenReturn(new BigDecimal("14.0"));
              when(rs.getInt("complaint_count")).thenReturn(0);
              when(rs.getBigDecimal("avg_rating")).thenReturn(new BigDecimal("4.50"));
              when(rs.getInt("review_count")).thenReturn(20);
              when(rs.getLong("gmv_period_paise")).thenReturn(100_000L);
              when(rs.getShort("consecutive_low_fill_days")).thenReturn((short) 0);
              when(rs.getString("fill_rate_trend")).thenReturn("STABLE");
              when(rs.getString("cancel_rate_trend")).thenReturn("STABLE");
              when(rs.getTimestamp("computed_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("alert_type")).thenReturn("LOW_FILL_RATE");
              when(rs.getObject("triggered_by")).thenReturn(Ids.newId());
              when(rs.getBigDecimal("threshold_value")).thenReturn(new BigDecimal("78.50"));
              when(rs.getString("message")).thenReturn("msg");
              Array channels = mock(Array.class);
              when(channels.getArray()).thenReturn(new String[] {"WHATSAPP", "IN_APP"});
              when(rs.getArray("channels")).thenReturn(channels);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(snapshots.find(PID, "30D")).isPresent();
    assertThat(alerts.lastSentAt(PID, "LOW_FILL_RATE", NOW.minusSeconds(3600))).isPresent();
    assertThat(JdbcPerformanceAlertStore.readChannels(mock(ResultSet.class))).isEmpty();
    Array channels = mock(Array.class);
    when(channels.getArray()).thenReturn(null);
    ResultSet withNullChannels = mock(ResultSet.class);
    when(withNullChannels.getArray("channels")).thenReturn(channels);
    when(withNullChannels.getObject("id")).thenReturn(Ids.newId());
    when(withNullChannels.getObject("pharmacy_id")).thenReturn(PID);
    when(withNullChannels.getString("alert_type")).thenReturn("LOW_FILL_RATE");
    when(withNullChannels.getObject("triggered_by")).thenReturn(null);
    when(withNullChannels.getBigDecimal("threshold_value")).thenReturn(new BigDecimal("1"));
    when(withNullChannels.getString("message")).thenReturn(null);
    when(withNullChannels.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
    assertThat(JdbcPerformanceAlertStore.readChannels(withNullChannels)).isEmpty();
    assertThat(JdbcPerformanceAlertStore.mapRow(withNullChannels, 0).channels()).isEmpty();

    Array emptyValues = mock(Array.class);
    when(emptyValues.getArray()).thenReturn(null);
    ResultSet rsValuesNull = mock(ResultSet.class);
    when(rsValuesNull.getArray("channels")).thenReturn(emptyValues);
    assertThat(JdbcPerformanceAlertStore.readChannels(rsValuesNull)).isEmpty();

    Array withValues = mock(Array.class);
    when(withValues.getArray()).thenReturn(new String[] {"WHATSAPP"});
    ResultSet rsWithValues = mock(ResultSet.class);
    when(rsWithValues.getArray("channels")).thenReturn(withValues);
    assertThat(JdbcPerformanceAlertStore.readChannels(rsWithValues)).containsExactly("WHATSAPP");
  }
}
