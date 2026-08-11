package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.DeviceTokenStore;
import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDeviceTokenStore implements DeviceTokenStore {

  private static final String SELECT =
      """
      SELECT id, user_id, user_type, token, platform, device_id, is_active,
             registered_at, last_refreshed_at
      FROM device_tokens
      """;

  private final JdbcTemplate jdbc;

  public JdbcDeviceTokenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public DeviceToken upsert(
      UUID userId,
      NotificationUserType userType,
      String token,
      DevicePlatform platform,
      String deviceId,
      Instant now) {
    // Account switch without logout: same FCM token must not stay active for another user.
    jdbc.update(
        """
        UPDATE device_tokens
        SET is_active = FALSE, last_refreshed_at = ?
        WHERE token = ? AND is_active = TRUE
          AND NOT (user_id = ? AND user_type = ?)
        """,
        Timestamp.from(now),
        token,
        userId,
        userType.name());
    Optional<DeviceToken> existing = findByUserAndDevice(userId, userType, deviceId);
    if (existing.isPresent()) {
      DeviceToken prev = existing.get();
      jdbc.update(
          """
          UPDATE device_tokens
          SET token = ?, platform = ?, is_active = TRUE, last_refreshed_at = ?
          WHERE id = ?
          """,
          token,
          platform.name(),
          Timestamp.from(now),
          prev.id());
      return new DeviceToken(
          prev.id(), userId, userType, token, platform, deviceId, true, prev.registeredAt(), now);
    }
    UUID id = Ids.newId();
    jdbc.update(
        """
        INSERT INTO device_tokens (
          id, user_id, user_type, token, platform, device_id, is_active,
          registered_at, last_refreshed_at
        ) VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)
        """,
        id,
        userId,
        userType.name(),
        token,
        platform.name(),
        deviceId,
        Timestamp.from(now),
        Timestamp.from(now));
    return new DeviceToken(id, userId, userType, token, platform, deviceId, true, now, now);
  }

  @Override
  public Optional<DeviceToken> findByUserAndDevice(
      UUID userId, NotificationUserType userType, String deviceId) {
    List<DeviceToken> rows =
        jdbc.query(
            SELECT + " WHERE user_id = ? AND user_type = ? AND device_id = ?",
            (rs, i) -> map(rs),
            userId,
            userType.name(),
            deviceId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean deactivate(
      UUID userId, NotificationUserType userType, String deviceId, Instant now) {
    int n =
        jdbc.update(
            """
            UPDATE device_tokens
            SET is_active = FALSE, last_refreshed_at = ?
            WHERE user_id = ? AND user_type = ? AND device_id = ? AND is_active = TRUE
            """,
            Timestamp.from(now),
            userId,
            userType.name(),
            deviceId);
    return n > 0;
  }

  @Override
  public void deactivateById(UUID tokenId, Instant now) {
    jdbc.update(
        """
        UPDATE device_tokens
        SET is_active = FALSE, last_refreshed_at = ?
        WHERE id = ?
        """,
        Timestamp.from(now),
        tokenId);
  }

  @Override
  public List<DeviceToken> findActiveByUser(UUID userId, NotificationUserType userType) {
    return jdbc.query(
        SELECT + " WHERE user_id = ? AND user_type = ? AND is_active = TRUE",
        (rs, i) -> map(rs),
        userId,
        userType.name());
  }

  @Override
  public List<DeviceToken> findActiveByUserType(NotificationUserType userType) {
    return jdbc.query(
        SELECT + " WHERE user_type = ? AND is_active = TRUE", (rs, i) -> map(rs), userType.name());
  }

  @Override
  public int countActiveByUserType(NotificationUserType userType) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM device_tokens WHERE user_type = ? AND is_active = TRUE",
            Integer.class,
            userType.name());
    return n == null ? 0 : n;
  }

  private static DeviceToken map(ResultSet rs) throws SQLException {
    return new DeviceToken(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("user_id"),
        NotificationUserType.valueOf(rs.getString("user_type")),
        rs.getString("token"),
        DevicePlatform.valueOf(rs.getString("platform")),
        rs.getString("device_id"),
        rs.getBoolean("is_active"),
        rs.getTimestamp("registered_at").toInstant(),
        rs.getTimestamp("last_refreshed_at").toInstant());
  }
}
