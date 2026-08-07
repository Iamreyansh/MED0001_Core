package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAdminNoteStore implements AdminNoteStore {

  private final JdbcTemplate jdbc;

  public JdbcAdminNoteStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(NoteRow row) {
    jdbc.update(
        """
        INSERT INTO admin_note (id, pharmacy_id, note, is_flagged, added_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.note(),
        row.flagged(),
        row.addedBy(),
        Timestamp.from(row.createdAt()));
  }

  @Override
  public List<NoteRow> list(UUID pharmacyId, Boolean flaggedOnly, int limit, int offset) {
    String sql =
        """
        SELECT id, pharmacy_id, note, is_flagged, added_by, created_at
        FROM admin_note
        WHERE pharmacy_id = ?
        """;
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (flaggedOnly != null && flaggedOnly) {
      sql += " AND is_flagged = TRUE";
    }
    sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?";
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql, (rs, rowNum) -> mapRow(rs, rowNum), args.toArray());
  }

  @Override
  public long count(UUID pharmacyId, Boolean flaggedOnly) {
    String sql = "SELECT COUNT(*) FROM admin_note WHERE pharmacy_id = ?";
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (flaggedOnly != null && flaggedOnly) {
      sql += " AND is_flagged = TRUE";
    }
    Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
    return total == null ? 0L : total;
  }

  public static NoteRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new NoteRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("note"),
        rs.getBoolean("is_flagged"),
        (UUID) rs.getObject("added_by"),
        ts(rs, "created_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
