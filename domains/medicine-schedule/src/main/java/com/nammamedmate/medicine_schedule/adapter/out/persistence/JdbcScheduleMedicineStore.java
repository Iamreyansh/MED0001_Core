package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcScheduleMedicineStore implements ScheduleMedicineStore {

  private static final TypeReference<List<Map<String, String>>> SLOTS_TYPE =
      new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT id, customer_id, member_id, master_medicine_id, medicine_name, strength, dose, form,
             dose_slots::text AS dose_slots, food_instruction, duration_type, duration_days,
             started_on_date, ended_on_date, condition_name, prescribed_by, units_in_hand,
             refill_remind_at_units, notes, is_active, created_at, updated_at
      FROM schedule_medicine
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RowMapper<ScheduleMedicineRecord> rowMapper = this::mapRow;

  public JdbcScheduleMedicineStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public ScheduleMedicineRecord insert(ScheduleMedicineRecord medicine) {
    jdbc.update(
        """
        INSERT INTO schedule_medicine (
          id, customer_id, member_id, master_medicine_id, medicine_name, strength, dose, form,
          dose_slots, food_instruction, duration_type, duration_days, started_on_date,
          ended_on_date, condition_name, prescribed_by, units_in_hand, refill_remind_at_units,
          notes, is_active, created_at, updated_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?,
          ?::jsonb, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?, ?
        )
        """,
        medicine.id(),
        medicine.customerId(),
        medicine.memberId(),
        medicine.masterMedicineId(),
        medicine.medicineName(),
        medicine.strength(),
        medicine.dose(),
        medicine.form(),
        toJson(medicine.doseSlots()),
        medicine.foodInstruction(),
        medicine.durationType(),
        medicine.durationDays(),
        Date.valueOf(medicine.startedOnDate()),
        medicine.endedOnDate() == null ? null : Date.valueOf(medicine.endedOnDate()),
        medicine.conditionName(),
        medicine.prescribedBy(),
        medicine.unitsInHand(),
        medicine.refillRemindAtUnits(),
        medicine.notes(),
        medicine.active(),
        Timestamp.from(medicine.createdAt()),
        Timestamp.from(medicine.updatedAt()));
    return medicine;
  }

  @Override
  public ScheduleMedicineRecord update(ScheduleMedicineRecord medicine) {
    jdbc.update(
        """
        UPDATE schedule_medicine SET
          master_medicine_id = ?, medicine_name = ?, strength = ?, dose = ?, form = ?,
          dose_slots = ?::jsonb, food_instruction = ?, duration_type = ?, duration_days = ?,
          started_on_date = ?, ended_on_date = ?, condition_name = ?, prescribed_by = ?,
          units_in_hand = ?, refill_remind_at_units = ?, notes = ?, is_active = ?, updated_at = ?
        WHERE id = ?
        """,
        medicine.masterMedicineId(),
        medicine.medicineName(),
        medicine.strength(),
        medicine.dose(),
        medicine.form(),
        toJson(medicine.doseSlots()),
        medicine.foodInstruction(),
        medicine.durationType(),
        medicine.durationDays(),
        Date.valueOf(medicine.startedOnDate()),
        medicine.endedOnDate() == null ? null : Date.valueOf(medicine.endedOnDate()),
        medicine.conditionName(),
        medicine.prescribedBy(),
        medicine.unitsInHand(),
        medicine.refillRemindAtUnits(),
        medicine.notes(),
        medicine.active(),
        Timestamp.from(medicine.updatedAt()),
        medicine.id());
    return medicine;
  }

  @Override
  public Optional<ScheduleMedicineRecord> findById(UUID medicineId) {
    List<ScheduleMedicineRecord> rows = jdbc.query(SELECT + " WHERE id = ?", rowMapper, medicineId);
    return rows.stream().findFirst();
  }

  @Override
  public List<ScheduleMedicineRecord> listByMember(
      UUID customerId, UUID memberId, boolean activeOnly) {
    if (activeOnly) {
      return jdbc.query(
          SELECT
              + """
              WHERE customer_id = ? AND member_id = ? AND is_active = TRUE
              ORDER BY created_at ASC
              """,
          rowMapper,
          customerId,
          memberId);
    }
    return jdbc.query(
        SELECT
            + """
            WHERE customer_id = ? AND member_id = ?
            ORDER BY is_active DESC, created_at ASC
            """,
        rowMapper,
        customerId,
        memberId);
  }

  @Override
  public List<ScheduleMedicineRecord> listActiveByMember(UUID memberId) {
    return jdbc.query(
        SELECT + " WHERE member_id = ? AND is_active = TRUE ORDER BY created_at ASC",
        rowMapper,
        memberId);
  }

  @Override
  public List<ScheduleMedicineRecord> listActiveByCustomer(UUID customerId) {
    return jdbc.query(
        SELECT + " WHERE customer_id = ? AND is_active = TRUE ORDER BY created_at ASC",
        rowMapper,
        customerId);
  }

  @Override
  public List<UUID> listCustomerIdsWithActiveMedicines() {
    return jdbc.query(
        """
        SELECT DISTINCT customer_id FROM schedule_medicine WHERE is_active = TRUE
        ORDER BY customer_id
        """,
        (rs, i) -> (UUID) rs.getObject("customer_id"));
  }

  @Override
  public int decrementUnitsInHand(UUID medicineId, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE schedule_medicine SET
          units_in_hand = units_in_hand - 1, updated_at = ?
        WHERE id = ? AND units_in_hand > 0
        """,
        Timestamp.from(updatedAt),
        medicineId);
  }

  @Override
  public Optional<Integer> decrementUnitsBy(UUID medicineId, int amount, Instant updatedAt) {
    if (amount <= 0) {
      return findById(medicineId).map(ScheduleMedicineRecord::unitsInHand);
    }
    int updated =
        jdbc.update(
            """
            UPDATE schedule_medicine SET
              units_in_hand = GREATEST(0, units_in_hand - ?), updated_at = ?
            WHERE id = ?
            """,
            amount,
            Timestamp.from(updatedAt),
            medicineId);
    if (updated == 0) {
      return Optional.empty();
    }
    return findById(medicineId).map(ScheduleMedicineRecord::unitsInHand);
  }

  @Override
  public List<ScheduleMedicineRecord> listActiveWithSupplyTracking() {
    return jdbc.query(
        SELECT
            + """
            WHERE is_active = TRUE
              AND units_in_hand > 0
              AND refill_remind_at_units > 0
            ORDER BY created_at ASC
            """,
        rowMapper);
  }

  @Override
  public List<ScheduleMedicineRecord> listRefillAlertsNeedingPush(LocalDate today) {
    return jdbc.query(
        SELECT
            + """
            WHERE is_active = TRUE
              AND refill_remind_at_units > 0
              AND units_in_hand <= refill_remind_at_units
              AND (last_refill_alert_pushed_on IS NULL OR last_refill_alert_pushed_on < ?)
            ORDER BY created_at ASC
            """,
        rowMapper,
        Date.valueOf(today));
  }

  @Override
  public void markRefillAlertPushedOn(UUID medicineId, LocalDate pushedOn, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE schedule_medicine SET
          last_refill_alert_pushed_on = ?, updated_at = ?
        WHERE id = ?
        """,
        Date.valueOf(pushedOn),
        Timestamp.from(updatedAt),
        medicineId);
  }

  @Override
  public int softArchiveByMember(UUID memberId, LocalDate endedOn, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE schedule_medicine SET
          is_active = FALSE, ended_on_date = ?, updated_at = ?
        WHERE member_id = ? AND is_active = TRUE
        """,
        Date.valueOf(endedOn),
        Timestamp.from(updatedAt),
        memberId);
  }

  private ScheduleMedicineRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Date ended = rs.getDate("ended_on_date");
    return new ScheduleMedicineRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("member_id"),
        (UUID) rs.getObject("master_medicine_id"),
        rs.getString("medicine_name"),
        rs.getString("strength"),
        rs.getString("dose"),
        rs.getString("form"),
        parseSlots(rs.getString("dose_slots")),
        rs.getString("food_instruction"),
        rs.getString("duration_type"),
        (Integer) rs.getObject("duration_days"),
        rs.getDate("started_on_date").toLocalDate(),
        ended == null ? null : ended.toLocalDate(),
        rs.getString("condition_name"),
        rs.getString("prescribed_by"),
        rs.getInt("units_in_hand"),
        rs.getInt("refill_remind_at_units"),
        rs.getString("notes"),
        rs.getBoolean("is_active"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private String toJson(List<DoseSlot> slots) {
    try {
      return objectMapper.writeValueAsString(
          slots.stream()
              .map(s -> Map.of("slot", s.slot(), "reminder_time", s.reminderTime()))
              .toList());
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize dose_slots", ex);
    }
  }

  private List<DoseSlot> parseSlots(String json) {
    try {
      List<Map<String, String>> raw = objectMapper.readValue(json, SLOTS_TYPE);
      return raw.stream().map(m -> new DoseSlot(m.get("slot"), m.get("reminder_time"))).toList();
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new IllegalStateException("Failed to parse dose_slots", ex);
    }
  }
}
