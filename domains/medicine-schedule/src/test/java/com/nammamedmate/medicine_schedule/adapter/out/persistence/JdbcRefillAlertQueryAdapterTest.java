package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort.MedicineSummary;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort.RefillAlert;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcRefillAlertQueryAdapterTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T07:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void refillAlertsAndMedicines() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DoseLogStore doseLogs = mock(DoseLogStore.class);
    when(doseLogs.countsForMemberBetween(any(), any(), any()))
        .thenReturn(new TodayCounts(20, 17, 0, 3, 0));
    JdbcRefillAlertQueryAdapter adapter =
        new JdbcRefillAlertQueryAdapter(jdbc, mapper, doseLogs, CLOCK);
    UUID memberId = Ids.newId();
    UUID medicineId = Ids.newId();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(memberId)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              if (sql.contains("refill_remind_at_units")) {
                when(rs.getObject("id")).thenReturn(medicineId);
                when(rs.getString("medicine_name")).thenReturn("Metformin");
                when(rs.getString("strength")).thenReturn("500mg");
                when(rs.getString("form")).thenReturn("TABLET");
                when(rs.getInt("units_in_hand")).thenReturn(8);
                when(rs.getInt("refill_remind_at_units")).thenReturn(10);
                when(rs.getObject("master_medicine_id")).thenReturn(null);
                when(rs.getString("dose_slots"))
                    .thenReturn(
                        "[{\"slot\":\"MORNING\",\"reminder_time\":\"08:00\"},{\"slot\":\"NIGHT\",\"reminder_time\":\"21:00\"}]");
                return List.of(rowMapper.mapRow(rs, 0));
              }
              when(rs.getObject("id")).thenReturn(medicineId);
              when(rs.getString("medicine_name")).thenReturn("Metformin");
              when(rs.getString("dose")).thenReturn("1 tablet");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getString("dose_slots"))
                  .thenReturn("[{\"slot\":\"MORNING\",\"reminder_time\":\"08:00\"}]");
              when(rs.getBoolean("is_active")).thenReturn(true);
              return List.of(rowMapper.mapRow(rs, 0));
            });

    List<RefillAlert> alerts = adapter.refillAlerts(memberId);
    assertThat(alerts).hasSize(1);
    assertThat(alerts.getFirst().approxDaysLeft()).isEqualTo(4);

    List<MedicineSummary> medicines = adapter.medicines(memberId);
    assertThat(medicines).hasSize(1);
    assertThat(medicines.getFirst().medicineId()).isEqualTo(medicineId);
    assertThat(adapter.thisWeekAdherencePct(memberId)).isEqualTo(85.0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void badJsonYieldsEmptySlots() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DoseLogStore doseLogs = mock(DoseLogStore.class);
    JdbcRefillAlertQueryAdapter adapter =
        new JdbcRefillAlertQueryAdapter(jdbc, mapper, doseLogs, CLOCK);
    UUID memberId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(memberId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("medicine_name")).thenReturn("X");
              when(rs.getString("strength")).thenReturn(null);
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getInt("units_in_hand")).thenReturn(5);
              when(rs.getInt("refill_remind_at_units")).thenReturn(6);
              when(rs.getObject("master_medicine_id")).thenReturn(null);
              when(rs.getString("dose_slots")).thenReturn("not-json");
              return List.of(rowMapper.mapRow(rs, 0));
            });
    List<RefillAlert> alerts = adapter.refillAlerts(memberId);
    assertThat(alerts).hasSize(1);
    assertThat(alerts.getFirst().approxDaysLeft()).isNull();
    assertThat(alerts.getFirst().alertLevel()).isEqualTo("WARNING");
  }

  @Test
  @SuppressWarnings("unchecked")
  void criticalWhenApproxDaysLeftAtMostThree() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DoseLogStore doseLogs = mock(DoseLogStore.class);
    JdbcRefillAlertQueryAdapter adapter =
        new JdbcRefillAlertQueryAdapter(jdbc, mapper, doseLogs, CLOCK);
    UUID memberId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(memberId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("medicine_name")).thenReturn("Metformin");
              when(rs.getString("strength")).thenReturn("500mg");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getInt("units_in_hand")).thenReturn(6);
              when(rs.getInt("refill_remind_at_units")).thenReturn(10);
              when(rs.getObject("master_medicine_id")).thenReturn(Ids.newId());
              when(rs.getString("dose_slots"))
                  .thenReturn(
                      "[{\"slot\":\"MORNING\",\"reminder_time\":\"08:00\"},{\"slot\":\"NIGHT\",\"reminder_time\":\"21:00\"}]");
              return List.of(rowMapper.mapRow(rs, 0));
            });
    RefillAlert alert = adapter.refillAlerts(memberId).getFirst();
    assertThat(alert.approxDaysLeft()).isEqualTo(3);
    assertThat(alert.alertLevel()).isEqualTo("CRITICAL");
    assertThat(alert.canOrderOnline()).isTrue();
  }
}
