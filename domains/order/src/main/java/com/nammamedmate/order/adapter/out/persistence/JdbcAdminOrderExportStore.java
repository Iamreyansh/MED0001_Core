package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.AdminOrderExportStore;
import com.nammamedmate.order.domain.AdminOrderExportJob;
import com.nammamedmate.order.domain.ExportJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAdminOrderExportStore implements AdminOrderExportStore {

  private final JdbcTemplate jdbc;

  public JdbcAdminOrderExportStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public AdminOrderExportJob insert(AdminOrderExportJob job) {
    jdbc.update(
        """
        INSERT INTO admin_order_export_job (
          id, requested_by, filters, row_count, status, s3_key, created_at, completed_at
        ) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        """,
        job.id(),
        job.requestedBy(),
        job.filtersJson(),
        job.rowCount(),
        job.status().name(),
        job.s3Key(),
        Timestamp.from(job.createdAt()),
        job.completedAt() == null ? null : Timestamp.from(job.completedAt()));
    return job;
  }

  @Override
  public Optional<AdminOrderExportJob> findById(UUID jobId) {
    List<AdminOrderExportJob> rows =
        jdbc.query("SELECT * FROM admin_order_export_job WHERE id = ?", this::map, jobId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public List<AdminOrderExportJob> findByStatus(ExportJobStatus status, int limit) {
    return jdbc.query(
        """
        SELECT * FROM admin_order_export_job
        WHERE status = ?
        ORDER BY created_at ASC
        LIMIT ?
        """,
        this::map,
        status.name(),
        limit);
  }

  @Override
  public void markReady(UUID jobId, String s3Key, int rowCount, Instant completedAt) {
    jdbc.update(
        """
        UPDATE admin_order_export_job
        SET status = 'READY', s3_key = ?, row_count = ?, completed_at = ?
        WHERE id = ?
        """,
        s3Key,
        rowCount,
        Timestamp.from(completedAt),
        jobId);
  }

  @Override
  public void markFailed(UUID jobId, Instant completedAt) {
    jdbc.update(
        """
        UPDATE admin_order_export_job
        SET status = 'FAILED', completed_at = ?
        WHERE id = ?
        """,
        Timestamp.from(completedAt),
        jobId);
  }

  private AdminOrderExportJob map(ResultSet rs, int i) throws SQLException {
    Timestamp completed = rs.getTimestamp("completed_at");
    Integer rowCount = (Integer) rs.getObject("row_count");
    return new AdminOrderExportJob(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("requested_by"),
        rs.getString("filters"),
        rowCount,
        ExportJobStatus.valueOf(rs.getString("status")),
        rs.getString("s3_key"),
        rs.getTimestamp("created_at").toInstant(),
        completed == null ? null : completed.toInstant());
  }
}
