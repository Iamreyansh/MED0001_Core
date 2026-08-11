package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcScheduleMedicineStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void insertUpdateFindListArchive() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleMedicineStore store = new JdbcScheduleMedicineStore(jdbc, mapper);
    ScheduleMedicineRecord record = sample();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    when(jdbc.query(
            anyString(), any(RowMapper.class), eq(record.customerId()), eq(record.memberId())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.memberId())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
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
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), eq(record.memberId()))).thenReturn(1);

    assertThat(store.insert(record)).isEqualTo(record);
    assertThat(store.update(record)).isEqualTo(record);
    assertThat(store.findById(record.id())).contains(record);
    assertThat(store.listByMember(record.customerId(), record.memberId(), true)).hasSize(1);
    assertThat(store.listByMember(record.customerId(), record.memberId(), false)).hasSize(1);
    assertThat(store.listActiveByMember(record.memberId())).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    assertThat(store.listActiveByCustomer(record.customerId())).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("customer_id")).thenReturn(record.customerId());
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.listCustomerIdsWithActiveMedicines()).contains(record.customerId());

    when(jdbc.update(anyString(), any(), eq(record.id()))).thenReturn(1);
    assertThat(store.decrementUnitsInHand(record.id(), NOW)).isEqualTo(1);
    assertThat(store.softArchiveByMember(record.memberId(), LocalDate.parse("2026-07-24"), NOW))
        .isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void supplyTrackingHelpers() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleMedicineStore store = new JdbcScheduleMedicineStore(jdbc, mapper);
    ScheduleMedicineRecord record = sample();
    LocalDate today = LocalDate.parse("2026-07-24");

    when(jdbc.update(anyString(), eq(2), any(), eq(record.id()))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    assertThat(store.decrementUnitsBy(record.id(), 2, NOW)).contains(30);
    assertThat(store.decrementUnitsBy(record.id(), 0, NOW)).contains(30);
    when(jdbc.update(anyString(), eq(1), any(), eq(Ids.newId()))).thenReturn(0);
    assertThat(store.decrementUnitsBy(Ids.newId(), 1, NOW)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    assertThat(store.listActiveWithSupplyTracking()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(Date.valueOf(today))))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    assertThat(store.listRefillAlertsNeedingPush(today)).hasSize(1);

    store.markRefillAlertPushedOn(record.id(), today, NOW);
    verify(jdbc).update(anyString(), eq(Date.valueOf(today)), any(), eq(record.id()));
  }

  @Test
  void findEmpty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleMedicineStore store = new JdbcScheduleMedicineStore(jdbc, mapper);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findById(Ids.newId())).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsEndedOnDateAndNullDoseSlotsRecord() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleMedicineStore store = new JdbcScheduleMedicineStore(jdbc, mapper);
    ScheduleMedicineRecord withEnd =
        new ScheduleMedicineRecord(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            null,
            "Med",
            null,
            "1",
            "TABLET",
            null,
            "ANY",
            "DAYS",
            5,
            LocalDate.parse("2026-07-24"),
            LocalDate.parse("2026-07-29"),
            null,
            null,
            0,
            0,
            null,
            true,
            NOW,
            NOW);
    assertThat(withEnd.doseSlots()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(withEnd.id())))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              ResultSet rs = mockRs(withEnd);
              when(rs.getDate("ended_on_date")).thenReturn(Date.valueOf(withEnd.endedOnDate()));
              when(rs.getObject("duration_days")).thenReturn(5);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.findById(withEnd.id())).isPresent();
    store.insert(withEnd);
    store.update(withEnd);
  }

  @Test
  void toJsonAndParseFailures() {
    ObjectMapper broken = mock(ObjectMapper.class);
    try {
      when(broken.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    } catch (Exception ignored) {
    }
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleMedicineStore store = new JdbcScheduleMedicineStore(jdbc, broken);
    ScheduleMedicineRecord record = sample();
    assertThatThrownBy(() -> store.insert(record)).isInstanceOf(IllegalStateException.class);

    ObjectMapper brokenRead = mock(ObjectMapper.class);
    try {
      when(brokenRead.readValue(
              anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    } catch (Exception ignored) {
    }
    JdbcScheduleMedicineStore store2 = new JdbcScheduleMedicineStore(jdbc, brokenRead);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<ScheduleMedicineRecord> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(mockRs(record), 0));
            });
    assertThatThrownBy(() -> store2.findById(record.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ScheduleMedicineRecord sample() {
    return new ScheduleMedicineRecord(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        null,
        "Metformin",
        "500mg",
        "1 tablet",
        "TABLET",
        List.of(new DoseSlot("MORNING", "08:00"), new DoseSlot("NIGHT", "21:00")),
        "AFTER",
        "ONGOING",
        null,
        LocalDate.parse("2026-07-24"),
        null,
        null,
        null,
        30,
        10,
        null,
        true,
        NOW,
        NOW);
  }

  private static ResultSet mockRs(ScheduleMedicineRecord r) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getObject("customer_id")).thenReturn(r.customerId());
    when(rs.getObject("member_id")).thenReturn(r.memberId());
    when(rs.getObject("master_medicine_id")).thenReturn(r.masterMedicineId());
    when(rs.getString("medicine_name")).thenReturn(r.medicineName());
    when(rs.getString("strength")).thenReturn(r.strength());
    when(rs.getString("dose")).thenReturn(r.dose());
    when(rs.getString("form")).thenReturn(r.form());
    when(rs.getString("dose_slots"))
        .thenReturn(
            "[{\"slot\":\"MORNING\",\"reminder_time\":\"08:00\"},{\"slot\":\"NIGHT\",\"reminder_time\":\"21:00\"}]");
    when(rs.getString("food_instruction")).thenReturn(r.foodInstruction());
    when(rs.getString("duration_type")).thenReturn(r.durationType());
    when(rs.getObject("duration_days")).thenReturn(r.durationDays());
    when(rs.getDate("started_on_date")).thenReturn(Date.valueOf(r.startedOnDate()));
    when(rs.getDate("ended_on_date")).thenReturn(null);
    when(rs.getString("condition_name")).thenReturn(r.conditionName());
    when(rs.getString("prescribed_by")).thenReturn(r.prescribedBy());
    when(rs.getInt("units_in_hand")).thenReturn(r.unitsInHand());
    when(rs.getInt("refill_remind_at_units")).thenReturn(r.refillRemindAtUnits());
    when(rs.getString("notes")).thenReturn(r.notes());
    when(rs.getBoolean("is_active")).thenReturn(r.active());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(r.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(r.updatedAt()));
    return rs;
  }
}
