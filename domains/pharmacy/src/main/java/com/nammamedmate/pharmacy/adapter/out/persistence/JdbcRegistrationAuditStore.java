package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.RegistrationAuditStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRegistrationAuditStore implements RegistrationAuditStore {

  private final JdbcTemplate jdbc;

  public JdbcRegistrationAuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(
      UUID id,
      UUID pharmacyId,
      String email,
      String phone,
      String ip,
      String outcome,
      String errorCode,
      Instant at) {
    jdbc.update(
        """
        INSERT INTO pharmacy_registration_audit (
          id, pharmacy_id, email, phone, ip_address, outcome, error_code, created_at
        ) VALUES (?, ?, ?, ?, ?::inet, ?, ?, ?)
        """,
        id,
        pharmacyId,
        email,
        phone,
        ip == null || ip.isBlank() ? null : ip,
        outcome,
        errorCode,
        Timestamp.from(at));
  }
}
