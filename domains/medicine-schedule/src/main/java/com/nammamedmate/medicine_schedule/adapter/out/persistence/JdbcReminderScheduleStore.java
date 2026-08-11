package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcReminderScheduleStore implements ReminderScheduleStore {

  private static final String SELECT =
      """
      SELECT id, medicine_id, customer_id, dose_log_id, scheduled_at, channel, status,
             notification_id, sent_at, delivered_at, opened_at, created_at
      FROM reminder_schedule
      """;

  private final JdbcTemplate jdbc;
  private final RowMapper<ReminderRecord> rowMapper = this::mapRow;

  public JdbcReminderScheduleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ReminderRecord upsertScheduled(ReminderRecord draft) {
    Optional<ReminderRecord> existing = findByDoseLogId(draft.doseLogId());
    if (existing.isPresent()) {
      ReminderRecord cur = existing.get();
      if ("SCHEDULED".equals(cur.status()) || "CANCELLED".equals(cur.status())) {
        jdbc.update(
            """
            UPDATE reminder_schedule SET
              scheduled_at = ?, status = 'SCHEDULED', channel = ?,
              notification_id = NULL, sent_at = NULL, delivered_at = NULL, opened_at = NULL
            WHERE id = ?
            """,
            Timestamp.from(draft.scheduledAt()),
            draft.channel(),
            cur.id());
        return findByDoseLogId(draft.doseLogId()).orElse(cur);
      }
      return cur;
    }
    jdbc.update(
        """
        INSERT INTO reminder_schedule (
          id, medicine_id, customer_id, dose_log_id, scheduled_at, channel, status,
          notification_id, sent_at, delivered_at, opened_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'SCHEDULED', NULL, NULL, NULL, NULL, ?)
        """,
        draft.id(),
        draft.medicineId(),
        draft.customerId(),
        draft.doseLogId(),
        Timestamp.from(draft.scheduledAt()),
        draft.channel(),
        Timestamp.from(draft.createdAt()));
    return draft;
  }

  @Override
  public Optional<ReminderRecord> findByDoseLogId(UUID doseLogId) {
    List<ReminderRecord> rows = jdbc.query(SELECT + " WHERE dose_log_id = ?", rowMapper, doseLogId);
    return rows.stream().findFirst();
  }

  @Override
  public int cancelFutureScheduled(UUID medicineId, Instant now) {
    return jdbc.update(
        """
        UPDATE reminder_schedule SET status = 'CANCELLED'
        WHERE medicine_id = ? AND status = 'SCHEDULED' AND scheduled_at >= ?
        """,
        medicineId,
        Timestamp.from(now));
  }

  @Override
  public int cancelFutureNotInSlots(UUID medicineId, List<String> keepSlots, Instant now) {
    if (keepSlots == null || keepSlots.isEmpty()) {
      return cancelFutureScheduled(medicineId, now);
    }
    String placeholders = String.join(",", keepSlots.stream().map(s -> "?").toList());
    Object[] args = new Object[keepSlots.size() + 2];
    args[0] = medicineId;
    args[1] = Timestamp.from(now);
    for (int i = 0; i < keepSlots.size(); i++) {
      args[i + 2] = keepSlots.get(i);
    }
    String sql =
        "UPDATE reminder_schedule rs SET status = 'CANCELLED' "
            + "FROM dose_log dl WHERE rs.dose_log_id = dl.id AND rs.medicine_id = ? "
            + "AND rs.status = 'SCHEDULED' AND rs.scheduled_at >= ? AND dl.slot NOT IN ("
            + placeholders
            + ")";
    return jdbc.update(sql, args);
  }

  @Override
  public List<ReminderRecord> findDueScheduled(Instant now, int limit) {
    return jdbc.query(
        SELECT
            + """
            WHERE status = 'SCHEDULED' AND scheduled_at <= ?
            ORDER BY scheduled_at ASC
            LIMIT ?
            """,
        rowMapper,
        Timestamp.from(now),
        limit);
  }

  @Override
  public void markSent(UUID reminderId, Instant sentAt, String notificationId) {
    jdbc.update(
        """
        UPDATE reminder_schedule SET status = 'SENT', sent_at = ?, notification_id = ?
        WHERE id = ?
        """,
        Timestamp.from(sentAt),
        notificationId,
        reminderId);
  }

  private ReminderRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp sent = rs.getTimestamp("sent_at");
    Timestamp delivered = rs.getTimestamp("delivered_at");
    Timestamp opened = rs.getTimestamp("opened_at");
    return new ReminderRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("medicine_id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("dose_log_id"),
        rs.getTimestamp("scheduled_at").toInstant(),
        rs.getString("channel"),
        rs.getString("status"),
        rs.getString("notification_id"),
        sent == null ? null : sent.toInstant(),
        delivered == null ? null : delivered.toInstant(),
        opened == null ? null : opened.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
