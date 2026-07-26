package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyEmailOtpStore implements PharmacyEmailOtpStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyEmailOtpStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OtpRecord record) {
    jdbc.update(
        """
        INSERT INTO pharmacy_email_otps (
          id, pharmacy_id, email, otp_hash, attempts, resend_count, expires_at,
          verified_at, locked_at, last_sent_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.email(),
        record.otpHash(),
        record.attempts(),
        record.resendCount(),
        Timestamp.from(record.expiresAt()),
        record.verifiedAt() == null ? null : Timestamp.from(record.verifiedAt()),
        record.lockedAt() == null ? null : Timestamp.from(record.lockedAt()),
        Timestamp.from(record.lastSentAt()),
        Timestamp.from(record.createdAt()));
  }

  @Override
  public void update(OtpRecord record) {
    jdbc.update(
        """
        UPDATE pharmacy_email_otps SET
          otp_hash = ?, attempts = ?, resend_count = ?, expires_at = ?,
          verified_at = ?, locked_at = ?, last_sent_at = ?
        WHERE id = ?
        """,
        record.otpHash(),
        record.attempts(),
        record.resendCount(),
        Timestamp.from(record.expiresAt()),
        record.verifiedAt() == null ? null : Timestamp.from(record.verifiedAt()),
        record.lockedAt() == null ? null : Timestamp.from(record.lockedAt()),
        Timestamp.from(record.lastSentAt()),
        record.id());
  }

  @Override
  public Optional<OtpRecord> findLatestByEmail(String email) {
    List<OtpRecord> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_email_otps WHERE email = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (rs, n) -> {
              Instant verifiedAt =
                  rs.getTimestamp("verified_at") == null
                      ? null
                      : rs.getTimestamp("verified_at").toInstant();
              Instant lockedAt =
                  rs.getTimestamp("locked_at") == null
                      ? null
                      : rs.getTimestamp("locked_at").toInstant();
              return new OtpRecord(
                  (UUID) rs.getObject("id"),
                  (UUID) rs.getObject("pharmacy_id"),
                  rs.getString("email"),
                  rs.getString("otp_hash"),
                  rs.getInt("attempts"),
                  rs.getInt("resend_count"),
                  rs.getTimestamp("expires_at").toInstant(),
                  verifiedAt,
                  lockedAt,
                  rs.getTimestamp("last_sent_at").toInstant(),
                  rs.getTimestamp("created_at").toInstant());
            },
            email);
    return rows.stream().findFirst();
  }
}
