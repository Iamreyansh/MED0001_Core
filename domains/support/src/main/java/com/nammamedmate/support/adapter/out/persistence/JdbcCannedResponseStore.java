package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.support.application.port.out.CannedResponseStore;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.TicketCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCannedResponseStore implements CannedResponseStore {

  private static final String SELECT =
      """
      SELECT id, title, category, body, shortcut_key, copy_count, last_used_at,
             created_by, deleted_at, created_at, updated_at
      FROM support_canned_responses
      """;

  private final JdbcTemplate jdbc;

  public JdbcCannedResponseStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CannedResponse insert(CannedResponse row) {
    try {
      jdbc.update(
          """
          INSERT INTO support_canned_responses (
            id, title, category, body, shortcut_key, copy_count, last_used_at,
            created_by, deleted_at, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          row.id(),
          row.title(),
          row.category().name(),
          row.body(),
          row.shortcutKey(),
          row.copyCount(),
          ts(row.lastUsedAt()),
          row.createdBy(),
          ts(row.deletedAt()),
          Timestamp.from(row.createdAt()),
          Timestamp.from(row.updatedAt()));
    } catch (DuplicateKeyException e) {
      throw new AppException("SHORTCUT_KEY_EXISTS", "Shortcut key already used", 409);
    }
    return row;
  }

  @Override
  public CannedResponse update(CannedResponse row) {
    try {
      int n =
          jdbc.update(
              """
              UPDATE support_canned_responses
              SET title = ?, category = ?, body = ?, shortcut_key = ?,
                  copy_count = ?, last_used_at = ?, deleted_at = ?, updated_at = ?
              WHERE id = ? AND deleted_at IS NULL
              """,
              row.title(),
              row.category().name(),
              row.body(),
              row.shortcutKey(),
              row.copyCount(),
              ts(row.lastUsedAt()),
              ts(row.deletedAt()),
              Timestamp.from(row.updatedAt()),
              row.id());
      if (n == 0) {
        throw new AppException("CANNED_RESPONSE_NOT_FOUND", "Canned response not found", 404);
      }
    } catch (DuplicateKeyException e) {
      throw new AppException("SHORTCUT_KEY_EXISTS", "Shortcut key already used", 409);
    }
    return row;
  }

  @Override
  public Optional<CannedResponse> findById(UUID id) {
    List<CannedResponse> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<CannedResponse> findByShortcut(String shortcutKey) {
    List<CannedResponse> rows =
        jdbc.query(
            SELECT + " WHERE shortcut_key = ? AND deleted_at IS NULL",
            (rs, i) -> map(rs),
            shortcutKey);
    return rows.stream().findFirst();
  }

  @Override
  public List<CannedResponse> list(ListFilter filter) {
    StringBuilder sql = new StringBuilder(SELECT);
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    // Prefer matching category first (AC-010), then title.
    if (filter.category() != null) {
      sql.append(" ORDER BY CASE WHEN category = ? THEN 0 ELSE 1 END, title ASC");
      args.add(filter.category().name());
    } else {
      sql.append(" ORDER BY title ASC");
    }
    sql.append(" LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(filter.offset());
    return jdbc.query(sql.toString(), args.toArray(), (rs, i) -> map(rs));
  }

  @Override
  public long count(ListFilter filter) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM support_canned_responses");
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public void recordUsage(UUID id, Instant usedAt) {
    jdbc.update(
        """
        UPDATE support_canned_responses
        SET copy_count = copy_count + 1, last_used_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(usedAt),
        Timestamp.from(usedAt),
        id);
  }

  private static void appendWhere(StringBuilder sql, List<Object> args, ListFilter filter) {
    sql.append(" WHERE deleted_at IS NULL");
    // Prefer-sort only: still return other categories so ORDER items can lead (AC-010).
    // Hard filter when q is blank and category set would hide cross-category shortcuts —
    // keep soft prefer via ORDER BY; optional exclusive filter when category AND no q.
    if (filter.category() != null && (filter.q() == null || filter.q().isBlank())) {
      sql.append(" AND category = ?");
      args.add(filter.category().name());
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      String like = "%" + filter.q().trim() + "%";
      sql.append(" AND (title ILIKE ? OR body ILIKE ? OR shortcut_key ILIKE ?)");
      args.add(like);
      args.add(like);
      args.add(like);
    }
  }

  private static CannedResponse map(ResultSet rs) throws SQLException {
    Timestamp lastUsed = rs.getTimestamp("last_used_at");
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new CannedResponse(
        (UUID) rs.getObject("id"),
        rs.getString("title"),
        TicketCategory.valueOf(rs.getString("category")),
        rs.getString("body"),
        rs.getString("shortcut_key"),
        rs.getInt("copy_count"),
        lastUsed == null ? null : lastUsed.toInstant(),
        (UUID) rs.getObject("created_by"),
        deleted == null ? null : deleted.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
