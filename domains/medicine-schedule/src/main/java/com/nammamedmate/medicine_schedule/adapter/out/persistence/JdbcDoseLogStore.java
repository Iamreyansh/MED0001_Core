package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcDoseLogStore implements DoseLogStore {

  private static final String SELECT =
      """
      SELECT id, medicine_id, customer_id, member_id, dose_date, slot, reminder_time,
             status, taken_at, is_locked, created_at, updated_at
      FROM dose_log
      """;

  private final JdbcTemplate jdbc;
  private final RowMapper<DoseLogRecord> rowMapper = this::mapRow;

  public JdbcDoseLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public DoseLogRecord upsertUpcoming(DoseLogRecord draft) {
    Optional<DoseLogRecord> existing =
        findByMedicineDateSlot(draft.medicineId(), draft.doseDate(), draft.slot());
    if (existing.isPresent()) {
      DoseLogRecord cur = existing.get();
      // Do not overwrite terminal statuses; still refresh reminder_time when UPCOMING.
      if ("UPCOMING".equals(cur.status())) {
        jdbc.update(
            """
            UPDATE dose_log SET reminder_time = ?, updated_at = ?
            WHERE id = ?
            """,
            Time.valueOf(draft.reminderTime()),
            Timestamp.from(draft.updatedAt()),
            cur.id());
        return findById(cur.id()).orElse(cur);
      }
      return cur;
    }
    jdbc.update(
        """
        INSERT INTO dose_log (
          id, medicine_id, customer_id, member_id, dose_date, slot, reminder_time,
          status, taken_at, is_locked, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'UPCOMING', NULL, FALSE, ?, ?)
        """,
        draft.id(),
        draft.medicineId(),
        draft.customerId(),
        draft.memberId(),
        Date.valueOf(draft.doseDate()),
        draft.slot(),
        Time.valueOf(draft.reminderTime()),
        Timestamp.from(draft.createdAt()),
        Timestamp.from(draft.updatedAt()));
    return draft;
  }

  @Override
  public Optional<DoseLogRecord> findByMedicineDateSlot(
      UUID medicineId, LocalDate doseDate, String slot) {
    List<DoseLogRecord> rows =
        jdbc.query(
            SELECT + " WHERE medicine_id = ? AND dose_date = ? AND slot = ?",
            rowMapper,
            medicineId,
            Date.valueOf(doseDate),
            slot);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DoseLogRecord> findById(UUID doseLogId) {
    List<DoseLogRecord> rows = jdbc.query(SELECT + " WHERE id = ?", rowMapper, doseLogId);
    return rows.stream().findFirst();
  }

  @Override
  public List<DoseLogRecord> listByMemberAndDate(UUID memberId, LocalDate doseDate) {
    return jdbc.query(
        SELECT
            + """
            WHERE member_id = ? AND dose_date = ?
            ORDER BY reminder_time ASC, medicine_id ASC
            """,
        rowMapper,
        memberId,
        Date.valueOf(doseDate));
  }

  @Override
  public List<DoseLogRecord> listUpcomingByMemberUntil(UUID memberId, Instant until) {
    return jdbc.query(
        SELECT
            + """
            WHERE member_id = ?
              AND status = 'UPCOMING'
              AND (
                (dose_date + reminder_time) AT TIME ZONE 'Asia/Kolkata'
              ) <= ?
            ORDER BY dose_date ASC, reminder_time ASC
            """,
        rowMapper,
        memberId,
        Timestamp.from(until));
  }

  @Override
  public DoseLogRecord updateStatus(
      UUID doseLogId, String status, Instant takenAt, boolean locked, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE dose_log SET status = ?, taken_at = ?, is_locked = ?, updated_at = ?
        WHERE id = ?
        """,
        status,
        takenAt == null ? null : Timestamp.from(takenAt),
        locked,
        Timestamp.from(updatedAt),
        doseLogId);
    return findById(doseLogId).orElseThrow();
  }

  @Override
  public int markMissedBefore(Instant cutoff, Instant updatedAt) {
    return jdbc.update(
        """
        UPDATE dose_log SET status = 'MISSED', is_locked = TRUE, updated_at = ?
        WHERE status = 'UPCOMING'
          AND ((dose_date + reminder_time) AT TIME ZONE 'Asia/Kolkata') < ?
        """,
        Timestamp.from(updatedAt),
        Timestamp.from(cutoff));
  }

  @Override
  public TodayCounts countsForMemberOn(UUID memberId, LocalDate doseDate) {
    return jdbc.query(
        """
            SELECT
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
              COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
              COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
              COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
            FROM dose_log
            WHERE member_id = ? AND dose_date = ?
            """,
        rs -> {
          if (!rs.next()) {
            return new TodayCounts(0, 0, 0, 0, 0);
          }
          return new TodayCounts(
              rs.getInt("total"),
              rs.getInt("taken"),
              rs.getInt("skipped"),
              rs.getInt("missed"),
              rs.getInt("upcoming"));
        },
        memberId,
        Date.valueOf(doseDate));
  }

  @Override
  public TodayCounts countsForMedicineOn(UUID medicineId, LocalDate doseDate) {
    return jdbc.query(
        """
            SELECT
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
              COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
              COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
              COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
            FROM dose_log
            WHERE medicine_id = ? AND dose_date = ?
            """,
        rs -> {
          if (!rs.next()) {
            return new TodayCounts(0, 0, 0, 0, 0);
          }
          return new TodayCounts(
              rs.getInt("total"),
              rs.getInt("taken"),
              rs.getInt("skipped"),
              rs.getInt("missed"),
              rs.getInt("upcoming"));
        },
        medicineId,
        Date.valueOf(doseDate));
  }

  @Override
  public List<DailyCounts> dailyCountsForMember(
      UUID memberId, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT
          dose_date,
          COUNT(*)::int AS total,
          COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
          COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
          COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
          COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
        FROM dose_log
        WHERE member_id = ?
          AND dose_date >= ?
          AND dose_date <= ?
        GROUP BY dose_date
        ORDER BY dose_date ASC
        """,
        (rs, i) ->
            new DailyCounts(
                rs.getDate("dose_date").toLocalDate(),
                rs.getInt("total"),
                rs.getInt("taken"),
                rs.getInt("skipped"),
                rs.getInt("missed"),
                rs.getInt("upcoming")),
        memberId,
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<DailyCounts> dailyCountsForMedicine(
      UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT
              dose_date,
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
              COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
              COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
              COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
            FROM dose_log
            WHERE medicine_id = ?
            """);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    args.add(medicineId);
    if (fromInclusive != null) {
      sql.append(" AND dose_date >= ?");
      args.add(Date.valueOf(fromInclusive));
    }
    if (toInclusive != null) {
      sql.append(" AND dose_date <= ?");
      args.add(Date.valueOf(toInclusive));
    }
    sql.append(" GROUP BY dose_date ORDER BY dose_date ASC");
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new DailyCounts(
                rs.getDate("dose_date").toLocalDate(),
                rs.getInt("total"),
                rs.getInt("taken"),
                rs.getInt("skipped"),
                rs.getInt("missed"),
                rs.getInt("upcoming")),
        args.toArray());
  }

  @Override
  public TodayCounts countsForMemberBetween(
      UUID memberId, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
            SELECT
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
              COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
              COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
              COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
            FROM dose_log
            WHERE member_id = ?
              AND dose_date >= ?
              AND dose_date <= ?
            """,
        rs -> {
          if (!rs.next()) {
            return new TodayCounts(0, 0, 0, 0, 0);
          }
          return new TodayCounts(
              rs.getInt("total"),
              rs.getInt("taken"),
              rs.getInt("skipped"),
              rs.getInt("missed"),
              rs.getInt("upcoming"));
        },
        memberId,
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public TodayCounts countsForMedicineBetween(
      UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken,
              COUNT(*) FILTER (WHERE status = 'SKIPPED')::int AS skipped,
              COUNT(*) FILTER (WHERE status = 'MISSED')::int AS missed,
              COUNT(*) FILTER (WHERE status = 'UPCOMING')::int AS upcoming
            FROM dose_log
            WHERE medicine_id = ?
            """);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    args.add(medicineId);
    if (fromInclusive != null) {
      sql.append(" AND dose_date >= ?");
      args.add(Date.valueOf(fromInclusive));
    }
    if (toInclusive != null) {
      sql.append(" AND dose_date <= ?");
      args.add(Date.valueOf(toInclusive));
    }
    return jdbc.query(
        sql.toString(),
        (org.springframework.jdbc.core.ResultSetExtractor<TodayCounts>)
            rs -> {
              if (!rs.next()) {
                return new TodayCounts(0, 0, 0, 0, 0);
              }
              return new TodayCounts(
                  rs.getInt("total"),
                  rs.getInt("taken"),
                  rs.getInt("skipped"),
                  rs.getInt("missed"),
                  rs.getInt("upcoming"));
            },
        args.toArray());
  }

  private DoseLogRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp taken = rs.getTimestamp("taken_at");
    return new DoseLogRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("medicine_id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("member_id"),
        rs.getDate("dose_date").toLocalDate(),
        rs.getString("slot"),
        rs.getTime("reminder_time").toLocalTime(),
        rs.getString("status"),
        taken == null ? null : taken.toInstant(),
        rs.getBoolean("is_locked"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
