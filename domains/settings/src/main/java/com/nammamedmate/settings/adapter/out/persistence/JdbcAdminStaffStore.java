package com.nammamedmate.settings.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.settings.application.port.out.AdminStaffStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAdminStaffStore implements AdminStaffStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminStaffStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<AdminStaffRow> findById(UUID id) {
    List<AdminStaffRow> rows =
        jdbc.query(
            """
            SELECT id, name, email, role, status, mfa_enabled, last_active_at, invited_by,
                   invite_expires_at, created_at, updated_at, deleted_at
            FROM admin_staff
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> mapRow(rs),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AdminStaffRow> findByEmail(String email) {
    List<AdminStaffRow> rows =
        jdbc.query(
            """
            SELECT id, name, email, role, status, mfa_enabled, last_active_at, invited_by,
                   invite_expires_at, created_at, updated_at, deleted_at
            FROM admin_staff
            WHERE lower(email) = lower(?) AND deleted_at IS NULL
            """,
            (rs, i) -> mapRow(rs),
            email);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<InviterRef> findInviter(UUID id) {
    List<InviterRef> rows =
        jdbc.query(
            """
            SELECT id, name FROM admin_staff WHERE id = ?
            """,
            (rs, i) -> new InviterRef((UUID) rs.getObject("id"), rs.getString("name")),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean emailExists(String email) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_staff WHERE lower(email) = lower(?)", Long.class, email);
    return count != null && count > 0;
  }

  @Override
  public long countActiveSuperAdmins() {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM admin_staff
            WHERE role = 'admin_super' AND status = 'ACTIVE' AND deleted_at IS NULL
            """,
            Long.class);
    return count == null ? 0L : count;
  }

  @Override
  public PageResult list(String role, String status, String search, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    if (role != null) {
      where.append(" AND role = ? ");
      args.add(role);
    }
    if (status != null) {
      where.append(" AND status = ? ");
      args.add(status);
    }
    if (search != null) {
      where.append(" AND (lower(name) LIKE ? OR lower(email) LIKE ?) ");
      String like = "%" + search.toLowerCase() + "%";
      args.add(like);
      args.add(like);
    }

    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM admin_staff" + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(Math.max(0, (page - 1) * limit));
    List<AdminStaffRow> items =
        jdbc.query(
            """
            SELECT id, name, email, role, status, mfa_enabled, last_active_at, invited_by,
                   invite_expires_at, created_at, updated_at, deleted_at
            FROM admin_staff
            """
                + where
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> mapRow(rs),
            pageArgs.toArray());
    return new PageResult(items, totalCount);
  }

  @Override
  public void insertInvited(
      UUID id,
      String name,
      String email,
      String role,
      UUID invitedBy,
      String inviteTokenHash,
      Instant inviteExpiresAt,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO admin_staff (
          id, name, email, password_hash, role, status, mfa_enabled,
          invited_by, invite_token_hash, invite_expires_at, created_at, updated_at
        ) VALUES (?, ?, ?, NULL, ?, 'INVITED', FALSE, ?, ?, ?, ?, ?)
        """,
        id,
        name,
        email,
        role,
        invitedBy,
        inviteTokenHash,
        Timestamp.from(inviteExpiresAt),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Override
  public void refreshInvite(
      UUID id,
      String name,
      String role,
      UUID invitedBy,
      String inviteTokenHash,
      Instant inviteExpiresAt,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE admin_staff
        SET name = ?, role = ?, invited_by = ?, invite_token_hash = ?,
            invite_expires_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL AND status = 'INVITED'
        """,
        name,
        role,
        invitedBy,
        inviteTokenHash,
        Timestamp.from(inviteExpiresAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void update(UUID id, String name, String role, String status, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE admin_staff
        SET name = ?, role = ?, status = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        name,
        role,
        status,
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE admin_staff
        SET deleted_at = ?, updated_at = ?, status = 'SUSPENDED'
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public void setResetToken(UUID id, String resetTokenHash, Instant expiresAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE admin_staff
        SET reset_token_hash = ?, reset_token_expires_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        resetTokenHash,
        Timestamp.from(expiresAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public List<AuditTrailEntry> listAuditTrail(UUID staffId) {
    return jdbc.query(
        """
        SELECT a.action, a.payload, a.created_at, s.name AS actor_name
        FROM audit_log a
        LEFT JOIN admin_staff s ON s.id = a.actor_id
        WHERE a.entity_type = 'admin_staff' AND a.entity_id = ?
        ORDER BY a.created_at DESC
        LIMIT 100
        """,
        (rs, i) -> {
          Map<String, Object> payload = readPayload(rs.getString("payload"));
          Object before = payload.get("before");
          Object after = payload.get("after");
          String from = extractField(before, payload, "from");
          String to = extractField(after, payload, "to");
          if (from == null) {
            from = firstString(before, "role", "status", "name");
          }
          if (to == null) {
            to = firstString(after, "role", "status", "name");
          }
          String by = rs.getString("actor_name");
          return new AuditTrailEntry(
              rs.getString("action"), from, to, by, rs.getTimestamp("created_at").toInstant());
        },
        staffId);
  }

  private static AdminStaffRow mapRow(ResultSet rs) throws SQLException {
    return new AdminStaffRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getString("role"),
        rs.getString("status"),
        rs.getBoolean("mfa_enabled"),
        ts(rs, "last_active_at"),
        (UUID) rs.getObject("invited_by"),
        ts(rs, "invite_expires_at"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"),
        ts(rs, "deleted_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private Map<String, Object> readPayload(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      return Map.of();
    }
  }

  @SuppressWarnings("unchecked")
  private static String extractField(Object nested, Map<String, Object> payload, String key) {
    if (payload.get(key) instanceof String s) {
      return s;
    }
    if (nested instanceof Map<?, ?> map) {
      Object v = map.get(key);
      return v == null ? null : String.valueOf(v);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static String firstString(Object nested, String... keys) {
    if (!(nested instanceof Map<?, ?> map)) {
      return null;
    }
    for (String key : keys) {
      Object v = map.get(key);
      if (v != null) {
        return String.valueOf(v);
      }
    }
    return null;
  }
}
