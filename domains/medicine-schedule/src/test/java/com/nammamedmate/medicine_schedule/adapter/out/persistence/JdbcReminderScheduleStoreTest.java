package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore.ReminderRecord;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcReminderScheduleStoreTest {

  private JdbcTemplate jdbc;
  private JdbcReminderScheduleStore store;
  private Instant now;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcReminderScheduleStore(jdbc);
    now = Instant.parse("2026-07-24T06:30:00Z");
  }

  @Test
  @SuppressWarnings("unchecked")
  void upsertInsertAndUpdate() {
    UUID doseLogId = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId))).thenReturn(List.of());
    ReminderRecord draft = draft(doseLogId, "SCHEDULED");
    assertThat(store.upsertScheduled(draft).id()).isEqualTo(draft.id());

    ReminderRecord existing = draft(doseLogId, "CANCELLED");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId)))
        .thenReturn(List.of(existing));
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    assertThat(store.upsertScheduled(draft).status()).isIn("SCHEDULED", "CANCELLED");

    ReminderRecord sent = draft(doseLogId, "SENT");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId))).thenReturn(List.of(sent));
    assertThat(store.upsertScheduled(draft).status()).isEqualTo("SENT");
  }

  @Test
  void cancelAndDueAndMarkSent() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);
    assertThat(store.cancelFutureScheduled(Ids.newId(), now)).isEqualTo(2);

    // empty keepSlots delegates to cancelFutureScheduled
    assertThat(store.cancelFutureNotInSlots(Ids.newId(), List.of(), now)).isEqualTo(2);
    assertThat(store.cancelFutureNotInSlots(Ids.newId(), null, now)).isEqualTo(2);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(store.cancelFutureNotInSlots(Ids.newId(), List.of("MORNING", "NIGHT"), now))
        .isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.findDueScheduled(now, 10)).isEmpty();

    store.markSent(Ids.newId(), now, "nid");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapRow() throws Exception {
    UUID id = Ids.newId();
    UUID doseLogId = Ids.newId();
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("medicine_id")).thenReturn(Ids.newId());
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("dose_log_id")).thenReturn(doseLogId);
    when(rs.getTimestamp("scheduled_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("channel")).thenReturn("PUSH");
    when(rs.getString("status")).thenReturn("SCHEDULED");
    when(rs.getString("notification_id")).thenReturn("nid");
    when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("opened_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId)))
        .thenAnswer(
            inv -> {
              RowMapper<ReminderRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    ReminderRecord mapped = store.findByDoseLogId(doseLogId).orElseThrow();
    assertThat(mapped.sentAt()).isEqualTo(now);
    assertThat(mapped.deliveredAt()).isEqualTo(now);
    assertThat(mapped.openedAt()).isEqualTo(now);

    // orElse(cur) path when find after update returns empty briefly
    ReminderRecord scheduled = draft(doseLogId, "SCHEDULED");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId)))
        .thenReturn(List.of(scheduled))
        .thenReturn(List.of());
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    assertThat(store.upsertScheduled(draft(doseLogId, "SCHEDULED")).id()).isEqualTo(scheduled.id());
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapRowNullTimestamps() throws Exception {
    UUID doseLogId = Ids.newId();
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("medicine_id")).thenReturn(Ids.newId());
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("dose_log_id")).thenReturn(doseLogId);
    when(rs.getTimestamp("scheduled_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("channel")).thenReturn("PUSH");
    when(rs.getString("status")).thenReturn("SCHEDULED");
    when(rs.getString("notification_id")).thenReturn(null);
    when(rs.getTimestamp("sent_at")).thenReturn(null);
    when(rs.getTimestamp("delivered_at")).thenReturn(null);
    when(rs.getTimestamp("opened_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(doseLogId)))
        .thenAnswer(
            inv -> {
              RowMapper<ReminderRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    ReminderRecord mapped = store.findByDoseLogId(doseLogId).orElseThrow();
    assertThat(mapped.sentAt()).isNull();
    assertThat(mapped.deliveredAt()).isNull();
    assertThat(mapped.openedAt()).isNull();
  }

  private ReminderRecord draft(UUID doseLogId, String status) {
    return new ReminderRecord(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        doseLogId,
        now,
        "PUSH",
        status,
        null,
        null,
        null,
        null,
        now);
  }
}
