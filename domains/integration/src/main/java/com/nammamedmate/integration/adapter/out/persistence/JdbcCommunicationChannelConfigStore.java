package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CommunicationChannelConfigStore;
import com.nammamedmate.integration.domain.CommunicationChannelConfig;
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
public class JdbcCommunicationChannelConfigStore implements CommunicationChannelConfigStore {

  private final JdbcTemplate jdbc;

  public JdbcCommunicationChannelConfigStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<CommunicationChannelConfig> findAll() {
    return jdbc.query(
        """
        SELECT * FROM communication_channel_configs
         ORDER BY CASE channel
           WHEN 'PUSH' THEN 1 WHEN 'SMS' THEN 2 WHEN 'WHATSAPP' THEN 3 ELSE 4 END
        """,
        this::mapRow);
  }

  @Override
  public Optional<CommunicationChannelConfig> findByChannel(String channel) {
    List<CommunicationChannelConfig> rows =
        jdbc.query(
            "SELECT * FROM communication_channel_configs WHERE channel = ?", this::mapRow, channel);
    return rows.stream().findFirst();
  }

  @Override
  public void update(CommunicationChannelConfig config) {
    jdbc.update(
        """
        UPDATE communication_channel_configs SET
          is_enabled = ?,
          provider = ?,
          fallback_provider = ?,
          secrets_manager_key = ?,
          daily_send_limit = ?,
          daily_sent_count = ?,
          current_status = ?,
          last_health_check_at = ?,
          updated_by = ?,
          updated_at = ?
         WHERE channel = ?
        """,
        config.enabled(),
        config.provider(),
        config.fallbackProvider(),
        config.secretsManagerKey(),
        config.dailySendLimit(),
        config.dailySentCount(),
        config.currentStatus(),
        ts(config.lastHealthCheckAt()),
        config.updatedBy(),
        Timestamp.from(config.updatedAt()),
        config.channel());
  }

  @Override
  public void resetAllDailySentCounts() {
    jdbc.update("UPDATE communication_channel_configs SET daily_sent_count = 0");
  }

  private CommunicationChannelConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CommunicationChannelConfig(
        rs.getString("channel"),
        rs.getBoolean("is_enabled"),
        rs.getString("provider"),
        rs.getString("fallback_provider"),
        rs.getString("secrets_manager_key"),
        rs.getInt("daily_send_limit"),
        rs.getInt("daily_sent_count"),
        rs.getString("current_status"),
        instant(rs.getTimestamp("last_health_check_at")),
        (UUID) rs.getObject("updated_by"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
