package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyNoticeStore implements PharmacyNoticeStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyNoticeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(NoticeRow row) {
    jdbc.update(
        """
        INSERT INTO pharmacy_notice (
            id, pharmacy_id, channels, subject, message, template_name, priority,
            sent_by, sent_at, bulk_job_id
        ) VALUES (?, ?, ?::notice_channel[], ?, ?, ?, ?::notice_priority, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.channels().toArray(String[]::new),
        row.subject(),
        row.message(),
        row.templateName(),
        row.priority(),
        row.sentBy(),
        Timestamp.from(row.sentAt()),
        row.bulkJobId());
  }

  @Override
  public int countSince(UUID pharmacyId, Instant since) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_notice
            WHERE pharmacy_id = ? AND sent_at >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  @Override
  public Instant oldestSentAtSince(UUID pharmacyId, Instant since) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT sent_at FROM pharmacy_notice
            WHERE pharmacy_id = ? AND sent_at >= ?
            ORDER BY sent_at ASC LIMIT 1
            """,
            (rs, rowNum) -> ts(rs, "sent_at"),
            pharmacyId,
            Timestamp.from(since));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public static List<String> readChannels(ResultSet rs) throws SQLException {
    Array array = rs.getArray("channels");
    if (array == null) {
      return List.of();
    }
    String[] values = (String[]) array.getArray();
    return values == null ? List.of() : Arrays.asList(values);
  }

  public static NoticeRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new NoticeRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        readChannels(rs),
        rs.getString("subject"),
        rs.getString("message"),
        rs.getString("template_name"),
        rs.getString("priority"),
        (UUID) rs.getObject("sent_by"),
        ts(rs, "sent_at"),
        (UUID) rs.getObject("bulk_job_id"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
