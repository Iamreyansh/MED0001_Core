package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacySessionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacySessionStore implements PharmacySessionStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacySessionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(
      UUID sessionId,
      UUID userId,
      String refreshTokenHash,
      String clientIp,
      String userAgent,
      Instant now,
      Instant expiresAt,
      UUID pharmacyId) {
    jdbc.update(
        """
        INSERT INTO sessions (
          id, user_id, user_type, refresh_token_hash, pharmacy_id, token_scope,
          ip_address, user_agent, created_at, last_active_at, expires_at
        ) VALUES (?, ?, 'pharmacy_staff', ?, ?, 'full', ?::inet, ?, ?, ?, ?)
        """,
        sessionId,
        userId,
        refreshTokenHash,
        pharmacyId,
        clientIp == null ? "0.0.0.0" : (clientIp.isBlank() ? "0.0.0.0" : clientIp),
        userAgent,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(expiresAt));
  }
}
