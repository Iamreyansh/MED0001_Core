package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.medicine_schedule.application.ReminderRecalcService;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort;
import com.nammamedmate.medicine_schedule.domain.AdherenceMath;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRefillAlertQueryAdapter implements RefillAlertQueryPort {

  private static final TypeReference<List<Map<String, String>>> SLOTS_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final DoseLogStore doseLogs;
  private final Clock clock;

  public JdbcRefillAlertQueryAdapter(
      JdbcTemplate jdbc, ObjectMapper objectMapper, DoseLogStore doseLogs, Clock clock) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.doseLogs = doseLogs;
    this.clock = clock;
  }

  @Override
  public List<RefillAlert> refillAlerts(UUID memberId) {
    return jdbc.query(
        """
        SELECT id, medicine_name, strength, form, units_in_hand, refill_remind_at_units,
               master_medicine_id, dose_slots::text AS dose_slots
        FROM schedule_medicine
        WHERE member_id = ?
          AND is_active = TRUE
          AND refill_remind_at_units > 0
          AND units_in_hand <= refill_remind_at_units
        ORDER BY medicine_name ASC
        """,
        this::mapAlert,
        memberId);
  }

  @Override
  public List<MedicineSummary> medicines(UUID memberId) {
    return jdbc.query(
        """
        SELECT id, medicine_name, dose, form, dose_slots::text AS dose_slots, is_active
        FROM schedule_medicine
        WHERE member_id = ?
        ORDER BY is_active DESC, created_at ASC
        """,
        this::mapSummary,
        memberId);
  }

  @Override
  public Double thisWeekAdherencePct(UUID memberId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
    LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    TodayCounts week = doseLogs.countsForMemberBetween(memberId, weekStart, today);
    return AdherenceMath.pct(week.taken(), week.total());
  }

  private RefillAlert mapAlert(ResultSet rs, int rowNum) throws SQLException {
    int units = rs.getInt("units_in_hand");
    int remindAt = rs.getInt("refill_remind_at_units");
    int dosesPerDay = parseSlots(rs.getString("dose_slots")).size();
    Integer approx = dosesPerDay == 0 ? null : units / dosesPerDay;
    UUID masterId = (UUID) rs.getObject("master_medicine_id");
    String level = approx != null && approx <= 3 ? "CRITICAL" : "WARNING";
    return new RefillAlert(
        (UUID) rs.getObject("id"),
        rs.getString("medicine_name"),
        rs.getString("strength"),
        rs.getString("form"),
        units,
        remindAt,
        dosesPerDay,
        approx,
        masterId,
        masterId != null,
        level);
  }

  private MedicineSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
    return new MedicineSummary(
        (UUID) rs.getObject("id"),
        rs.getString("medicine_name"),
        rs.getString("dose"),
        rs.getString("form"),
        parseSlots(rs.getString("dose_slots")),
        rs.getBoolean("is_active"));
  }

  private List<DoseSlot> parseSlots(String json) {
    try {
      List<Map<String, String>> raw = objectMapper.readValue(json, SLOTS_TYPE);
      return raw.stream().map(m -> new DoseSlot(m.get("slot"), m.get("reminder_time"))).toList();
    } catch (JsonProcessingException | RuntimeException ex) {
      return List.of();
    }
  }
}
