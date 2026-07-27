package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore;
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
public class JdbcAutoKycJobStore implements AutoKycJobStore {

  private final JdbcTemplate jdbc;

  public JdbcAutoKycJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(AutoKycJobRecord job) {
    jdbc.update(
        """
        INSERT INTO auto_kyc_jobs (
          id, pharmacy_id, triggered_by, trigger_source, overall_status,
          auto_activated, triggered_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        job.id(),
        job.pharmacyId(),
        job.triggeredBy(),
        job.triggerSource(),
        job.overallStatus(),
        job.autoActivated(),
        Timestamp.from(job.triggeredAt()),
        job.completedAt() == null ? null : Timestamp.from(job.completedAt()));
  }

  @Override
  public Optional<AutoKycJobRecord> findById(UUID jobId) {
    List<AutoKycJobRecord> rows =
        jdbc.query("SELECT * FROM auto_kyc_jobs WHERE id = ?", this::mapRow, jobId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutoKycJobRecord> findLatestByPharmacy(UUID pharmacyId) {
    List<AutoKycJobRecord> rows =
        jdbc.query(
            """
            SELECT * FROM auto_kyc_jobs
            WHERE pharmacy_id = ?
            ORDER BY triggered_at DESC
            LIMIT 1
            """,
            this::mapRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutoKycJobRecord> findInProgressByPharmacy(UUID pharmacyId) {
    List<AutoKycJobRecord> rows =
        jdbc.query(
            """
            SELECT * FROM auto_kyc_jobs
            WHERE pharmacy_id = ? AND overall_status IN ('PENDING', 'PARTIAL')
            ORDER BY triggered_at DESC
            LIMIT 1
            """,
            this::mapRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void updateOverallStatus(UUID jobId, String overallStatus, Instant completedAt) {
    jdbc.update(
        """
        UPDATE auto_kyc_jobs SET overall_status = ?, completed_at = ?
        WHERE id = ?
        """,
        overallStatus,
        completedAt == null ? null : Timestamp.from(completedAt),
        jobId);
  }

  @Override
  public void markAutoActivated(UUID jobId, Instant completedAt) {
    jdbc.update(
        """
        UPDATE auto_kyc_jobs SET overall_status = 'PASS', auto_activated = TRUE, completed_at = ?
        WHERE id = ?
        """,
        Timestamp.from(completedAt),
        jobId);
  }

  private AutoKycJobRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AutoKycJobRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("triggered_by"),
        rs.getString("trigger_source"),
        rs.getString("overall_status"),
        rs.getBoolean("auto_activated"),
        ts(rs, "triggered_at"),
        ts(rs, "completed_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
