package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.domain.InAppNotificationType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyInAppNotificationService {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public PharmacyInAppNotificationService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void create(
      UUID pharmacyId, InAppNotificationType type, String title, String body, String actionUrl) {
    if (pharmacyId == null || title == null || title.isBlank() || body == null || body.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    jdbc.update(
        """
        INSERT INTO pharmacy_in_app_notifications (
          id, pharmacy_id, type, title, body, action_url, is_read, is_deleted,
          read_at, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, FALSE, FALSE, NULL, ?, ?)
        """,
        Ids.newId(),
        pharmacyId,
        type.name(),
        title.trim(),
        body.trim(),
        actionUrl == null || actionUrl.isBlank() ? null : actionUrl.trim(),
        Timestamp.from(now.plus(Duration.ofDays(type.retentionDays()))),
        Timestamp.from(now));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> unreadCount(MedmatePrincipal principal) {
    UUID pharmacyId = requirePharmacy(principal);
    Instant now = clock.instant();
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_in_app_notifications
            WHERE pharmacy_id = ? AND is_deleted = FALSE AND is_read = FALSE AND expires_at > ?
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(now));
    return Map.of("unread", n == null ? Long.valueOf(0) : n);
  }

  @Transactional(readOnly = true)
  public HistoryPage list(
      MedmatePrincipal principal, Boolean unreadOnly, Integer page, Integer limit) {
    UUID pharmacyId = requirePharmacy(principal);
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    Instant now = clock.instant();
    String unreadSql = Boolean.TRUE.equals(unreadOnly) ? " AND is_read = FALSE" : "";
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_in_app_notifications
            WHERE pharmacy_id = ? AND is_deleted = FALSE AND expires_at > ?
            """
                + unreadSql,
            Long.class,
            pharmacyId,
            Timestamp.from(now));
    List<Map<String, Object>> items =
        jdbc.query(
            """
            SELECT id, type, title, body, action_url, is_read, created_at
            FROM pharmacy_in_app_notifications
            WHERE pharmacy_id = ? AND is_deleted = FALSE AND expires_at > ?
            """
                + unreadSql
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("id", rs.getObject("id").toString());
              row.put("type", rs.getString("type"));
              row.put("title", rs.getString("title"));
              row.put("body", rs.getString("body"));
              row.put("action_url", rs.getString("action_url"));
              row.put("is_read", rs.getBoolean("is_read"));
              Timestamp created = rs.getTimestamp("created_at");
              row.put("created_at", created == null ? null : created.toInstant().toString());
              return row;
            },
            pharmacyId,
            Timestamp.from(now),
            pr.limit(),
            pr.offset());
    return new HistoryPage(
        Map.of("notifications", items), pr.page(), pr.limit(), total == null ? 0L : total);
  }

  @Transactional
  public Map<String, Object> markRead(MedmatePrincipal principal, UUID id) {
    UUID pharmacyId = requirePharmacy(principal);
    if (id == null) {
      throw new AppException("VALIDATION_ERROR", "id is required", 400);
    }
    Instant now = clock.instant();
    int updated =
        jdbc.update(
            """
            UPDATE pharmacy_in_app_notifications
            SET is_read = TRUE, read_at = ?
            WHERE id = ? AND pharmacy_id = ? AND is_deleted = FALSE
            """,
            Timestamp.from(now),
            id,
            pharmacyId);
    if (updated != 1) {
      throw new AppException("NOT_FOUND", "Notification not found", 404);
    }
    return Map.of("id", id.toString(), "is_read", true);
  }

  @Transactional
  public Map<String, Object> markAllRead(MedmatePrincipal principal) {
    UUID pharmacyId = requirePharmacy(principal);
    Instant now = clock.instant();
    int updated =
        jdbc.update(
            """
            UPDATE pharmacy_in_app_notifications
            SET is_read = TRUE, read_at = ?
            WHERE pharmacy_id = ? AND is_deleted = FALSE AND is_read = FALSE
            """,
            Timestamp.from(now),
            pharmacyId);
    return Map.of("updated", updated);
  }

  private static UUID requirePharmacy(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy context required", 403);
    }
    return principal.pharmacyId();
  }

  public record HistoryPage(Map<String, Object> data, int page, int limit, long total) {}
}
