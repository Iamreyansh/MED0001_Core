package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcScheduleDrugRegisterStore implements ScheduleDrugRegisterStore {

  private final JdbcTemplate jdbc;

  public JdbcScheduleDrugRegisterStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ScheduleDrugRegisterEntry e) {
    jdbc.update(
        """
        INSERT INTO schedule_drug_register_entry (
          id, sno, pharmacy_id, schedule, rx_id, rx_reference_no, order_id,
          patient_name, patient_age, prescriber_name, prescriber_reg_no,
          drug_name, batch_no, quantity_issued, unit, running_balance,
          pharmacy_license_no, dispensed_by_name, dispensed_by_user_id,
          dispensed_at, retention_expires_at, is_archived, created_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?, ?
        )
        """,
        e.id(),
        e.sno(),
        e.pharmacyId(),
        e.schedule(),
        e.rxId(),
        e.rxReferenceNo(),
        e.orderId(),
        e.patientName(),
        e.patientAge(),
        e.prescriberName(),
        e.prescriberRegNo(),
        e.drugName(),
        e.batchNo(),
        e.quantityIssued(),
        e.unit(),
        e.runningBalance(),
        e.pharmacyLicenseNo(),
        e.dispensedByName(),
        e.dispensedByUserId(),
        Timestamp.from(e.dispensedAt()),
        Timestamp.from(e.retentionExpiresAt()),
        e.archived(),
        Timestamp.from(e.createdAt()));
  }

  @Override
  public Optional<Integer> latestRunningBalance(UUID pharmacyId, String schedule, String drugName) {
    List<Integer> rows =
        jdbc.query(
            """
            SELECT running_balance
            FROM schedule_drug_register_entry
            WHERE pharmacy_id = ? AND schedule = ? AND lower(drug_name) = lower(?)
            ORDER BY dispensed_at DESC, sno DESC
            LIMIT 1
            """,
            (rs, i) -> rs.getInt("running_balance"),
            pharmacyId,
            schedule,
            drugName);
    return rows.stream().findFirst();
  }

  @Override
  public int nextSno(UUID pharmacyId, String schedule) {
    Integer max =
        jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(sno), 0) FROM schedule_drug_register_entry
            WHERE pharmacy_id = ? AND schedule = ?
            """,
            Integer.class,
            pharmacyId,
            schedule);
    return (max == null ? 0 : max) + 1;
  }

  @Override
  public int nextRxSeq(UUID pharmacyId, int year) {
    Integer max =
        jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(
              CAST(substring(rx_reference_no from '[0-9]+$') AS INTEGER)
            ), 0)
            FROM schedule_drug_register_entry
            WHERE pharmacy_id = ?
              AND rx_reference_no LIKE ?
            """,
            Integer.class,
            pharmacyId,
            "RX-" + year + "-%");
    return (max == null ? 0 : max) + 1;
  }

  @Override
  public Optional<PharmacySnapshot> pharmacy(UUID pharmacyId) {
    List<PharmacySnapshot> rows =
        jdbc.query(
            """
            SELECT COALESCE(business_name, name) AS display_name, drug_licence_number
            FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new PharmacySnapshot(
                    rs.getString("display_name"),
                    rs.getString("drug_licence_number") == null
                        ? ""
                        : rs.getString("drug_licence_number")),
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<String> staffName(UUID staffId) {
    List<String> rows =
        jdbc.query(
            "SELECT name FROM pharmacy_staff WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> rs.getString("name"),
            staffId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<UUID> orderIdForRx(UUID rxId, UUID pharmacyId) {
    List<UUID> rows =
        jdbc.query(
            """
            SELECT order_id FROM pharmacy_rx_queue
            WHERE rx_id = ? AND pharmacy_id = ? AND deleted_at IS NULL
              AND order_id IS NOT NULL
            LIMIT 1
            """,
            (rs, i) -> (UUID) rs.getObject("order_id"),
            rxId,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public ListPage list(ListFilter filter) {
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    appendSchedule(where, args, filter.schedule());
    appendFilters(where, args, filter);
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM schedule_drug_register_entry" + where,
            Long.class,
            args.toArray());
    Long qty =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity_issued), 0) FROM schedule_drug_register_entry" + where,
            Long.class,
            args.toArray());
    String sql =
        """
        SELECT * FROM schedule_drug_register_entry
        """
            + where
            + " ORDER BY dispensed_at DESC, sno DESC LIMIT ? OFFSET ?";
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(Math.max(0, (filter.page() - 1) * filter.limit()));
    List<ScheduleDrugRegisterEntry> entries =
        jdbc.query(sql, (rs, i) -> mapEntry(rs), pageArgs.toArray());
    return new ListPage(entries, total == null ? 0L : total, qty == null ? 0L : qty);
  }

  @Override
  public List<ScheduleDrugRegisterEntry> listAll(ListFilter filter) {
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    appendSchedule(where, args, filter.schedule());
    appendFilters(where, args, filter);
    String sql =
        """
        SELECT * FROM schedule_drug_register_entry
        """
            + where
            + " ORDER BY sno ASC, dispensed_at ASC";
    return jdbc.query(sql, (rs, i) -> mapEntry(rs), args.toArray());
  }

  @Override
  public int markArchivedPastRetention(Instant now) {
    return jdbc.update(
        """
        UPDATE schedule_drug_register_entry
        SET is_archived = TRUE
        WHERE is_archived = FALSE AND retention_expires_at < ?
        """,
        Timestamp.from(now));
  }

  @Override
  public void insertExportJob(ExportJob job) {
    jdbc.update(
        """
        INSERT INTO schedule_drug_register_export_job (
          id, pharmacy_id, schedule, from_date, to_date, status, storage_key,
          row_count, requested_by, generated_at, expires_at, error_message, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        job.id(),
        job.pharmacyId(),
        job.schedule(),
        Date.valueOf(job.fromDate()),
        Date.valueOf(job.toDate()),
        job.status(),
        job.storageKey(),
        job.rowCount(),
        job.requestedBy(),
        ts(job.generatedAt()),
        ts(job.expiresAt()),
        job.errorMessage(),
        Timestamp.from(job.createdAt()));
  }

  @Override
  public Optional<ExportJob> findExportJob(UUID jobId) {
    List<ExportJob> rows =
        jdbc.query(
            "SELECT * FROM schedule_drug_register_export_job WHERE id = ?",
            (rs, i) -> mapJob(rs),
            jobId);
    return rows.stream().findFirst();
  }

  @Override
  public void updateExportJob(ExportJob job) {
    jdbc.update(
        """
        UPDATE schedule_drug_register_export_job
        SET status = ?, storage_key = ?, row_count = ?, generated_at = ?,
            expires_at = ?, error_message = ?
        WHERE id = ?
        """,
        job.status(),
        job.storageKey(),
        job.rowCount(),
        ts(job.generatedAt()),
        ts(job.expiresAt()),
        job.errorMessage(),
        job.id());
  }

  @Override
  public boolean pharmacyExists(UUID pharmacyId) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacies WHERE id = ? AND deleted_at IS NULL",
            Long.class,
            pharmacyId);
    return n != null && n > 0;
  }

  private static void appendSchedule(StringBuilder where, List<Object> args, String schedule) {
    if (schedule == null || schedule.isBlank() || "ALL".equalsIgnoreCase(schedule.trim())) {
      where.append(" WHERE schedule IN ('H1','X') ");
      return;
    }
    where.append(" WHERE schedule = ? ");
    args.add(schedule);
  }

  private static void appendFilters(StringBuilder where, List<Object> args, ListFilter filter) {
    if (filter.pharmacyId() != null) {
      where.append(" AND pharmacy_id = ? ");
      args.add(filter.pharmacyId());
    }
    if (filter.drugName() != null && !filter.drugName().isBlank()) {
      where.append(" AND drug_name ILIKE ? ");
      args.add("%" + filter.drugName().trim() + "%");
    }
    if (filter.fromInclusive() != null) {
      where.append(" AND dispensed_at >= ? ");
      args.add(Timestamp.from(filter.fromInclusive()));
    }
    if (filter.toExclusive() != null) {
      where.append(" AND dispensed_at < ? ");
      args.add(Timestamp.from(filter.toExclusive()));
    }
  }

  private static ScheduleDrugRegisterEntry mapEntry(ResultSet rs) throws SQLException {
    Integer age = rs.getObject("patient_age") == null ? null : rs.getInt("patient_age");
    return new ScheduleDrugRegisterEntry(
        (UUID) rs.getObject("id"),
        rs.getInt("sno"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("schedule"),
        (UUID) rs.getObject("rx_id"),
        rs.getString("rx_reference_no"),
        (UUID) rs.getObject("order_id"),
        rs.getString("patient_name"),
        age,
        rs.getString("prescriber_name"),
        rs.getString("prescriber_reg_no"),
        rs.getString("drug_name"),
        rs.getString("batch_no"),
        rs.getInt("quantity_issued"),
        rs.getString("unit"),
        rs.getInt("running_balance"),
        rs.getString("pharmacy_license_no"),
        rs.getString("dispensed_by_name"),
        (UUID) rs.getObject("dispensed_by_user_id"),
        instant(rs.getTimestamp("dispensed_at")),
        instant(rs.getTimestamp("retention_expires_at")),
        rs.getBoolean("is_archived"),
        instant(rs.getTimestamp("created_at")));
  }

  private static ExportJob mapJob(ResultSet rs) throws SQLException {
    return new ExportJob(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("schedule"),
        rs.getDate("from_date").toLocalDate(),
        rs.getDate("to_date").toLocalDate(),
        rs.getString("status"),
        rs.getString("storage_key"),
        rs.getObject("row_count") == null ? null : rs.getInt("row_count"),
        (UUID) rs.getObject("requested_by"),
        instant(rs.getTimestamp("generated_at")),
        instant(rs.getTimestamp("expires_at")),
        rs.getString("error_message"),
        instant(rs.getTimestamp("created_at")));
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
