package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCategoryStore implements CategoryStore {

  private final JdbcTemplate jdbc;

  public JdbcCategoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<CategoryRow> list(boolean includeHidden, boolean includeDeleted) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT c.id, c.name, c.slug, c.icon_url, c.is_visible, c.display_order,
                   c.deleted_at, c.created_at, c.updated_at,
                   COALESCE(m.cnt, 0)::int AS medicine_count
            FROM medicine_category c
            LEFT JOIN (
              SELECT category_id, COUNT(*) AS cnt
              FROM medicine_master
              WHERE is_banned = FALSE
              GROUP BY category_id
            ) m ON m.category_id = c.id
            WHERE 1 = 1
            """);
    List<Object> args = new ArrayList<>();
    if (!includeDeleted) {
      sql.append(" AND c.deleted_at IS NULL ");
    }
    if (!includeHidden) {
      sql.append(" AND c.is_visible = TRUE ");
    }
    sql.append(" ORDER BY c.display_order ASC, c.name ASC ");
    return jdbc.query(sql.toString(), this::mapRow, args.toArray());
  }

  @Override
  public Optional<CategoryRow> findById(UUID id) {
    List<CategoryRow> rows =
        jdbc.query(
            """
            SELECT c.id, c.name, c.slug, c.icon_url, c.is_visible, c.display_order,
                   c.deleted_at, c.created_at, c.updated_at,
                   COALESCE(m.cnt, 0)::int AS medicine_count
            FROM medicine_category c
            LEFT JOIN (
              SELECT category_id, COUNT(*) AS cnt
              FROM medicine_master
              WHERE is_banned = FALSE
              GROUP BY category_id
            ) m ON m.category_id = c.id
            WHERE c.id = ?
            """,
            this::mapRow,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsBySlug(String slug) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_category WHERE slug = ?", Integer.class, slug);
    return count != null && count > 0;
  }

  @Override
  public boolean existsByName(String name) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_category WHERE name = ?", Integer.class, name);
    return count != null && count > 0;
  }

  @Override
  public boolean existsByNameExcluding(String name, UUID excludeId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_category WHERE name = ? AND id <> ?",
            Integer.class,
            name,
            excludeId);
    return count != null && count > 0;
  }

  @Override
  public int nextDisplayOrder() {
    Integer max =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(display_order), 0) FROM medicine_category WHERE deleted_at IS NULL",
            Integer.class);
    return (max == null ? 0 : max) + 1;
  }

  @Override
  public void insert(CategoryRow row) {
    jdbc.update(
        """
        INSERT INTO medicine_category
          (id, name, slug, icon_url, is_visible, display_order, deleted_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
        """,
        row.id(),
        row.name(),
        row.slug(),
        row.iconUrl(),
        row.visible(),
        row.displayOrder(),
        Timestamp.from(row.createdAt()),
        Timestamp.from(row.updatedAt()));
  }

  @Override
  public void update(
      UUID id,
      String name,
      String iconUrl,
      Boolean visible,
      Integer displayOrder,
      Instant updatedAt) {
    StringBuilder sql = new StringBuilder("UPDATE medicine_category SET updated_at = ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(updatedAt));
    if (name != null) {
      sql.append(", name = ?");
      args.add(name);
    }
    if (iconUrl != null) {
      sql.append(", icon_url = ?");
      args.add(iconUrl);
    }
    if (visible != null) {
      sql.append(", is_visible = ?");
      args.add(visible);
    }
    if (displayOrder != null) {
      sql.append(", display_order = ?");
      args.add(displayOrder);
    }
    sql.append(" WHERE id = ? AND deleted_at IS NULL");
    args.add(id);
    jdbc.update(sql.toString(), args.toArray());
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE medicine_category
        SET deleted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public int countExistingIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    String sql =
        "SELECT COUNT(*) FROM medicine_category WHERE deleted_at IS NULL AND id IN ("
            + placeholders
            + ")";
    Integer count = jdbc.queryForObject(sql, Integer.class, ids.toArray());
    return count == null ? 0 : count;
  }

  @Override
  public void reorder(List<ReorderItem> items, Instant updatedAt) {
    Timestamp ts = Timestamp.from(updatedAt);
    for (ReorderItem item : items) {
      jdbc.update(
          """
          UPDATE medicine_category
          SET display_order = ?, updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          item.displayOrder(),
          ts,
          item.id());
    }
  }

  private CategoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new CategoryRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("slug"),
        rs.getString("icon_url"),
        rs.getBoolean("is_visible"),
        rs.getInt("display_order"),
        deleted == null ? null : deleted.toInstant(),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant(),
        rs.getInt("medicine_count"));
  }
}
