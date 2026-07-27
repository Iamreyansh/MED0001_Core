package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyProfileOtpStore implements PharmacyProfileOtpStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyProfileOtpStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OtpRecord record) {
    jdbc.update(
        """
        INSERT INTO pharmacy_profile_otps (
          id, pharmacy_id, channel, target_value, otp_hash, expires_at, attempts, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.channel(),
        record.targetValue(),
        record.otpHash(),
        Timestamp.from(record.expiresAt()),
        record.attempts(),
        Timestamp.from(record.createdAt()));
  }

  @Override
  public void update(OtpRecord record) {
    jdbc.update(
        """
        UPDATE pharmacy_profile_otps SET otp_hash = ?, expires_at = ?, attempts = ?
        WHERE id = ?
        """,
        record.otpHash(),
        Timestamp.from(record.expiresAt()),
        record.attempts(),
        record.id());
  }

  @Override
  public void deleteByPharmacyAndChannel(UUID pharmacyId, String channel) {
    jdbc.update(
        "DELETE FROM pharmacy_profile_otps WHERE pharmacy_id = ? AND channel = ?",
        pharmacyId,
        channel);
  }

  @Override
  public Optional<OtpRecord> findLatest(UUID pharmacyId, String channel) {
    List<OtpRecord> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_profile_otps
            WHERE pharmacy_id = ? AND channel = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (rs, n) ->
                new OtpRecord(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("pharmacy_id"),
                    rs.getString("channel"),
                    rs.getString("target_value"),
                    rs.getString("otp_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getInt("attempts"),
                    rs.getTimestamp("created_at").toInstant()),
            pharmacyId,
            channel);
    return rows.stream().findFirst();
  }
}
