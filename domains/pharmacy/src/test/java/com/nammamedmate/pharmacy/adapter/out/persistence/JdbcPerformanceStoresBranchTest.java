package com.nammamedmate.pharmacy.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
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

class JdbcPerformanceStoresBranchTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
  private static final UUID PID = Ids.newId();

  @Test
  void snapshotFindEmptyAndPresent() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyPerformanceSnapshotStore store = new JdbcPharmacyPerformanceSnapshotStore(jdbc);

    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("pharmacy_id")).thenReturn(PID);
              when(rs.getString("period")).thenReturn("30D");
              when(rs.getObject("period_start", LocalDate.class))
                  .thenReturn(LocalDate.parse("2026-06-24"));
              when(rs.getObject("period_end", LocalDate.class))
                  .thenReturn(LocalDate.parse("2026-07-23"));
              when(rs.getInt("orders_received")).thenReturn(1);
              when(rs.getInt("orders_fulfilled")).thenReturn(1);
              when(rs.getInt("orders_cancelled")).thenReturn(0);
              when(rs.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.ONE);
              when(rs.getBigDecimal("on_time_prep_pct")).thenReturn(BigDecimal.ONE);
              when(rs.getBigDecimal("cancel_rate_pct")).thenReturn(BigDecimal.ONE);
              when(rs.getBigDecimal("out_of_stock_rate_pct")).thenReturn(BigDecimal.ONE);
              when(rs.getBigDecimal("avg_prep_minutes")).thenReturn(BigDecimal.ONE);
              when(rs.getInt("complaint_count")).thenReturn(0);
              when(rs.getBigDecimal("avg_rating")).thenReturn(BigDecimal.ONE);
              when(rs.getInt("review_count")).thenReturn(0);
              when(rs.getLong("gmv_period_paise")).thenReturn(0L);
              when(rs.getShort("consecutive_low_fill_days")).thenReturn((short) 0);
              when(rs.getString("fill_rate_trend")).thenReturn("STABLE");
              when(rs.getString("cancel_rate_trend")).thenReturn("STABLE");
              when(rs.getTimestamp("computed_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.find(PID, "30D")).isEmpty();
    assertThat(store.find(PID, "30D")).isPresent();

    store.upsert(
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            1,
            1,
            0,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            0,
            BigDecimal.ONE,
            0,
            0L,
            (short) 0,
            "STABLE",
            "STABLE",
            NOW),
        NOW);
  }

  @Test
  void alertLastSentEmptyAndPresent() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPerformanceAlertStore store = new JdbcPerformanceAlertStore(jdbc);

    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.lastSentAt(PID, "LOW_FILL_RATE", NOW.minusSeconds(60))).isEmpty();
    assertThat(store.lastSentAt(PID, "LOW_FILL_RATE", NOW.minusSeconds(60))).isPresent();

    ResultSet nullSent = mock(ResultSet.class);
    when(nullSent.getTimestamp("sent_at")).thenReturn(null);
    when(nullSent.getObject("id")).thenReturn(Ids.newId());
    when(nullSent.getObject("pharmacy_id")).thenReturn(PID);
    when(nullSent.getString("alert_type")).thenReturn("LOW_FILL_RATE");
    when(nullSent.getObject("triggered_by")).thenReturn(null);
    when(nullSent.getBigDecimal("threshold_value")).thenReturn(BigDecimal.ONE);
    when(nullSent.getString("message")).thenReturn(null);
    when(nullSent.getArray("channels")).thenReturn(null);
    assertThat(JdbcPerformanceAlertStore.mapRow(nullSent, 0).sentAt()).isNull();

    Array array = mock(Array.class);
    when(array.getArray()).thenReturn(null);
    ResultSet rs = mock(ResultSet.class);
    when(rs.getArray("channels")).thenReturn(array);
    assertThat(JdbcPerformanceAlertStore.readChannels(rs)).isEmpty();

    Array emptyValues = mock(Array.class);
    when(emptyValues.getArray()).thenReturn(null);
    ResultSet rsValuesNull = mock(ResultSet.class);
    when(rsValuesNull.getArray("channels")).thenReturn(emptyValues);
    assertThat(JdbcPerformanceAlertStore.readChannels(rsValuesNull)).isEmpty();

    Array withValues = mock(Array.class);
    when(withValues.getArray()).thenReturn(new String[] {"IN_APP"});
    ResultSet rsWithValues = mock(ResultSet.class);
    when(rsWithValues.getArray("channels")).thenReturn(withValues);
    assertThat(JdbcPerformanceAlertStore.readChannels(rsWithValues)).containsExactly("IN_APP");
  }
}
