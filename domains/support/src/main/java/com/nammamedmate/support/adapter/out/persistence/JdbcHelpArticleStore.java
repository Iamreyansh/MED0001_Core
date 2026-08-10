package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.support.application.port.out.HelpArticleStore;
import com.nammamedmate.support.domain.HelpArticle;
import com.nammamedmate.support.domain.TicketCategory;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcHelpArticleStore implements HelpArticleStore {

  private static final String SELECT =
      """
      SELECT id, title, category, content_markdown, tags, is_published, view_count,
             deflection_count, created_by, deleted_at, created_at, updated_at
      FROM support_help_articles
      """;

  private final JdbcTemplate jdbc;

  public JdbcHelpArticleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public HelpArticle insert(HelpArticle row) {
    jdbc.update(
        """
        INSERT INTO support_help_articles (
          id, title, category, content_markdown, tags, is_published, view_count,
          deflection_count, created_by, deleted_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.title(),
        row.category().name(),
        row.contentMarkdown(),
        JdbcDisputeStore.toTextArrayLiteral(row.tags()),
        row.published(),
        row.viewCount(),
        row.deflectionCount(),
        row.createdBy(),
        ts(row.deletedAt()),
        Timestamp.from(row.createdAt()),
        Timestamp.from(row.updatedAt()));
    return row;
  }

  @Override
  public HelpArticle update(HelpArticle row) {
    int n =
        jdbc.update(
            """
            UPDATE support_help_articles
            SET title = ?, category = ?, content_markdown = ?, tags = ?::text[],
                is_published = ?, view_count = ?, deflection_count = ?,
                deleted_at = ?, updated_at = ?
            WHERE id = ? AND deleted_at IS NULL
            """,
            row.title(),
            row.category().name(),
            row.contentMarkdown(),
            JdbcDisputeStore.toTextArrayLiteral(row.tags()),
            row.published(),
            row.viewCount(),
            row.deflectionCount(),
            ts(row.deletedAt()),
            Timestamp.from(row.updatedAt()),
            row.id());
    if (n == 0) {
      throw new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404);
    }
    return row;
  }

  @Override
  public Optional<HelpArticle> findById(UUID id) {
    List<HelpArticle> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public List<HelpArticle> list(ListFilter filter) {
    StringBuilder sql = new StringBuilder(SELECT);
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    if (filter.publicOnly()) {
      sql.append(" ORDER BY title ASC");
    } else {
      sql.append(" ORDER BY deflection_count DESC, title ASC");
    }
    sql.append(" LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(filter.offset());
    return jdbc.query(sql.toString(), args.toArray(), (rs, i) -> map(rs));
  }

  @Override
  public long count(ListFilter filter) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM support_help_articles");
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public List<CategoryCount> publishedCategoryCounts(String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT category, COUNT(*) AS article_count
            FROM support_help_articles
            WHERE deleted_at IS NULL AND is_published = TRUE
            """);
    List<Object> args = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim() + "%";
      sql.append(" AND (title ILIKE ? OR content_markdown ILIKE ? OR ? = ANY(tags))");
      args.add(like);
      args.add(like);
      args.add(q.trim().toLowerCase());
    }
    sql.append(" GROUP BY category ORDER BY category ASC");
    return jdbc.query(
        sql.toString(),
        args.toArray(),
        (rs, i) -> new CategoryCount(rs.getString("category"), rs.getLong("article_count")));
  }

  @Override
  public int incrementViewCount(UUID id) {
    Integer n =
        jdbc.queryForObject(
            """
            UPDATE support_help_articles
            SET view_count = view_count + 1, updated_at = NOW()
            WHERE id = ? AND deleted_at IS NULL AND is_published = TRUE
            RETURNING view_count
            """,
            Integer.class,
            id);
    return n == null ? 0 : n;
  }

  @Override
  public int incrementDeflectionCount(UUID id) {
    Integer n =
        jdbc.queryForObject(
            """
            UPDATE support_help_articles
            SET deflection_count = deflection_count + 1, updated_at = NOW()
            WHERE id = ? AND deleted_at IS NULL AND is_published = TRUE
            RETURNING deflection_count
            """,
            Integer.class,
            id);
    return n == null ? 0 : n;
  }

  private static void appendWhere(StringBuilder sql, List<Object> args, ListFilter filter) {
    sql.append(" WHERE deleted_at IS NULL");
    if (filter.publicOnly()) {
      sql.append(" AND is_published = TRUE");
    }
    if (filter.category() != null) {
      sql.append(" AND category = ?");
      args.add(filter.category().name());
    }
    if (filter.published() != null && !filter.publicOnly()) {
      sql.append(" AND is_published = ?");
      args.add(filter.published());
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      String like = "%" + filter.q().trim() + "%";
      sql.append(" AND (title ILIKE ? OR content_markdown ILIKE ? OR ? = ANY(tags))");
      args.add(like);
      args.add(like);
      args.add(filter.q().trim().toLowerCase());
    }
  }

  private static HelpArticle map(ResultSet rs) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new HelpArticle(
        (UUID) rs.getObject("id"),
        rs.getString("title"),
        TicketCategory.valueOf(rs.getString("category")),
        rs.getString("content_markdown"),
        readTags(rs.getArray("tags")),
        rs.getBoolean("is_published"),
        rs.getInt("view_count"),
        rs.getInt("deflection_count"),
        (UUID) rs.getObject("created_by"),
        deleted == null ? null : deleted.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static List<String> readTags(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] s) {
      return List.of(s);
    }
    if (raw instanceof Object[] objs) {
      List<String> out = new ArrayList<>(objs.length);
      for (Object o : objs) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }

  private static Timestamp ts(java.time.Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
