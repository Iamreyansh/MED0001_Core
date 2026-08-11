package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DoseLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class JdbcDoseLogStoreTest {

  private JdbcTemplate jdbc;
  private JdbcDoseLogStore store;
  private UUID id;
  private UUID medicineId;
  private Instant now;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcDoseLogStore(jdbc);
    id = Ids.newId();
    medicineId = Ids.newId();
    now = Instant.parse("2026-07-24T06:30:00Z");
  }

  @Test
  @SuppressWarnings("unchecked")
  void upsertInsertsWhenMissing() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    DoseLogRecord draft = draft("UPCOMING");
    DoseLogRecord saved = store.upsertUpcoming(draft);
    assertThat(saved.id()).isEqualTo(id);
  }

  @Test
  @SuppressWarnings("unchecked")
  void upsertUpdatesUpcoming() {
    DoseLogRecord existing = draft("UPCOMING");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of(existing));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of(existing));
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    DoseLogRecord saved =
        store.upsertUpcoming(
            new DoseLogRecord(
                Ids.newId(),
                medicineId,
                existing.customerId(),
                existing.memberId(),
                existing.doseDate(),
                existing.slot(),
                LocalTime.of(9, 0),
                "UPCOMING",
                null,
                false,
                now,
                now));
    assertThat(saved.id()).isEqualTo(id);
  }

  @Test
  @SuppressWarnings("unchecked")
  void upsertKeepsTerminal() {
    DoseLogRecord existing =
        new DoseLogRecord(
            id,
            medicineId,
            Ids.newId(),
            Ids.newId(),
            LocalDate.of(2026, 7, 24),
            "MORNING",
            LocalTime.of(8, 0),
            "TAKEN",
            now,
            false,
            now,
            now);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of(existing));
    assertThat(store.upsertUpcoming(draft("UPCOMING")).status()).isEqualTo("TAKEN");
  }

  @Test
  void markMissedAndCounts() throws Exception {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
    assertThat(store.markMissedBefore(now, now)).isEqualTo(3);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getInt("total")).thenReturn(5);
    when(rs.getInt("taken")).thenReturn(2);
    when(rs.getInt("skipped")).thenReturn(1);
    when(rs.getInt("missed")).thenReturn(1);
    when(rs.getInt("upcoming")).thenReturn(1);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              return ex.extractData(rs);
            });
    TodayCounts c = store.countsForMemberOn(Ids.newId(), LocalDate.of(2026, 7, 24));
    assertThat(c.total()).isEqualTo(5);

    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              return ex.extractData(empty);
            });
    assertThat(store.countsForMedicineOn(medicineId, LocalDate.of(2026, 7, 24)).total()).isZero();

    ResultSet full = mock(ResultSet.class);
    when(full.next()).thenReturn(true);
    when(full.getInt("total")).thenReturn(2);
    when(full.getInt("taken")).thenReturn(1);
    when(full.getInt("skipped")).thenReturn(0);
    when(full.getInt("missed")).thenReturn(0);
    when(full.getInt("upcoming")).thenReturn(1);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              return ex.extractData(full);
            });
    assertThat(store.countsForMedicineOn(medicineId, LocalDate.of(2026, 7, 24)).taken())
        .isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listAndUpdate() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.listByMemberAndDate(Ids.newId(), LocalDate.of(2026, 7, 24))).isEmpty();
    assertThat(store.listUpcomingByMemberUntil(Ids.newId(), now)).isEmpty();

    DoseLogRecord existing = draft("UPCOMING");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of(existing));
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.updateStatus(id, "TAKEN", now, false, now).status()).isEqualTo("UPCOMING");
    assertThat(store.updateStatus(id, "SKIPPED", null, false, now).status()).isEqualTo("UPCOMING");

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("medicine_id")).thenReturn(medicineId);
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("member_id")).thenReturn(Ids.newId());
    when(rs.getDate("dose_date")).thenReturn(java.sql.Date.valueOf("2026-07-24"));
    when(rs.getString("slot")).thenReturn("MORNING");
    when(rs.getTime("reminder_time")).thenReturn(Time.valueOf("08:00:00"));
    when(rs.getString("status")).thenReturn("UPCOMING");
    when(rs.getTimestamp("taken_at")).thenReturn(Timestamp.from(now));
    when(rs.getBoolean("is_locked")).thenReturn(false);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<DoseLogRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findById(id).orElseThrow().takenAt()).isEqualTo(now);
  }

  @Test
  void countsMemberEmptyAndTakenNull() throws Exception {
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              return ex.extractData(empty);
            });
    assertThat(store.countsForMemberOn(Ids.newId(), LocalDate.of(2026, 7, 24)).total()).isZero();

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("medicine_id")).thenReturn(medicineId);
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("member_id")).thenReturn(Ids.newId());
    when(rs.getDate("dose_date")).thenReturn(java.sql.Date.valueOf("2026-07-24"));
    when(rs.getString("slot")).thenReturn("MORNING");
    when(rs.getTime("reminder_time")).thenReturn(Time.valueOf("08:00:00"));
    when(rs.getString("status")).thenReturn("UPCOMING");
    when(rs.getTimestamp("taken_at")).thenReturn(null);
    when(rs.getBoolean("is_locked")).thenReturn(false);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<DoseLogRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().takenAt()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void dailyAndRangeAggregates() throws Exception {
    UUID memberId = Ids.newId();
    LocalDate from = LocalDate.of(2026, 7, 1);
    LocalDate to = LocalDate.of(2026, 7, 31);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(memberId), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("dose_date")).thenReturn(java.sql.Date.valueOf("2026-07-02"));
              when(rs.getInt("total")).thenReturn(4);
              when(rs.getInt("taken")).thenReturn(3);
              when(rs.getInt("skipped")).thenReturn(0);
              when(rs.getInt("missed")).thenReturn(1);
              when(rs.getInt("upcoming")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.dailyCountsForMember(memberId, from, to)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(medicineId), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("dose_date")).thenReturn(java.sql.Date.valueOf("2026-07-02"));
              when(rs.getInt("total")).thenReturn(2);
              when(rs.getInt("taken")).thenReturn(2);
              when(rs.getInt("skipped")).thenReturn(0);
              when(rs.getInt("missed")).thenReturn(0);
              when(rs.getInt("upcoming")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.dailyCountsForMedicine(medicineId, from, to)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(medicineId))).thenReturn(List.of());
    assertThat(store.dailyCountsForMedicine(medicineId, null, null)).isEmpty();

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getInt("total")).thenReturn(10);
    when(rs.getInt("taken")).thenReturn(8);
    when(rs.getInt("skipped")).thenReturn(1);
    when(rs.getInt("missed")).thenReturn(1);
    when(rs.getInt("upcoming")).thenReturn(0);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              ResultSet empty = mock(ResultSet.class);
              when(empty.next()).thenReturn(false);
              return ex.extractData(empty);
            });
    assertThat(store.countsForMemberBetween(memberId, from, to).total()).isZero();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              ResultSet full = mock(ResultSet.class);
              when(full.next()).thenReturn(true);
              when(full.getInt("total")).thenReturn(10);
              when(full.getInt("taken")).thenReturn(8);
              when(full.getInt("skipped")).thenReturn(1);
              when(full.getInt("missed")).thenReturn(1);
              when(full.getInt("upcoming")).thenReturn(0);
              return ex.extractData(full);
            });
    assertThat(store.countsForMemberBetween(memberId, from, to).taken()).isEqualTo(8);

    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(medicineId)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              return ex.extractData(empty);
            });
    assertThat(store.countsForMedicineBetween(medicineId, null, null).total()).isZero();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(medicineId), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<TodayCounts> ex = inv.getArgument(1);
              ResultSet full = mock(ResultSet.class);
              when(full.next()).thenReturn(true);
              when(full.getInt("total")).thenReturn(5);
              when(full.getInt("taken")).thenReturn(5);
              when(full.getInt("skipped")).thenReturn(0);
              when(full.getInt("missed")).thenReturn(0);
              when(full.getInt("upcoming")).thenReturn(0);
              return ex.extractData(full);
            });
    assertThat(store.countsForMedicineBetween(medicineId, from, to).taken()).isEqualTo(5);
  }

  private DoseLogRecord draft(String status) {
    return new DoseLogRecord(
        id,
        medicineId,
        Ids.newId(),
        Ids.newId(),
        LocalDate.of(2026, 7, 24),
        "MORNING",
        LocalTime.of(8, 0),
        status,
        null,
        false,
        now,
        now);
  }
}
