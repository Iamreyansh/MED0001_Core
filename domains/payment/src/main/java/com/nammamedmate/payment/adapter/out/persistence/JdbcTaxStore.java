package com.nammamedmate.payment.adapter.out.persistence;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.TaxStorePort;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaxStore implements TaxStorePort {

  private final JdbcTemplate jdbc;

  public JdbcTaxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<TaxFilingRecord> findFiling(UUID filingId) {
    List<TaxFilingRecord> rows =
        jdbc.query(
            """
            SELECT * FROM tax_filing WHERE id = ? AND deleted_at IS NULL
            """,
            this::mapFiling,
            filingId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<TaxFilingRecord> findFilingByTypeAndPeriod(String filingType, String period) {
    List<TaxFilingRecord> rows =
        jdbc.query(
            """
            SELECT * FROM tax_filing
            WHERE filing_type = ? AND period = ? AND deleted_at IS NULL
            """,
            this::mapFiling,
            filingType,
            period);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public List<TaxFilingRecord> listFilings(Integer year, String status) {
    StringBuilder sql = new StringBuilder("SELECT * FROM tax_filing WHERE deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    if (year != null) {
      sql.append(" AND (period LIKE ? OR period LIKE ?) ");
      args.add(year + "-%");
      args.add("Q%-" + year);
    }
    if (status != null && !status.isBlank()) {
      // OVERDUE filter applied in service via display overlay; stored may still be PENDING
      if ("OVERDUE".equalsIgnoreCase(status.trim())) {
        sql.append(" AND status IN ('PENDING','OVERDUE') AND due_date < CURRENT_DATE ");
      } else if ("PENDING".equalsIgnoreCase(status.trim())) {
        sql.append(" AND status = 'PENDING' AND due_date >= CURRENT_DATE ");
      } else {
        sql.append(" AND status = ? ");
        args.add(status.trim().toUpperCase());
      }
    }
    sql.append(" ORDER BY due_date DESC, created_at DESC ");
    return jdbc.query(sql.toString(), this::mapFiling, args.toArray());
  }

  @Override
  public void insertFiling(TaxFilingRecord filing) {
    jdbc.update(
        """
        INSERT INTO tax_filing (
          id, filing_type, period, due_date, status, filed_at, reference_number, notes,
          marked_by, generated_files, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
        """,
        filing.id(),
        filing.filingType(),
        filing.period(),
        java.sql.Date.valueOf(filing.dueDate()),
        filing.status(),
        filing.filedAt() == null ? null : Timestamp.from(filing.filedAt()),
        filing.referenceNumber(),
        filing.notes(),
        filing.markedBy(),
        filing.generatedFilesJson(),
        Timestamp.from(filing.createdAt()),
        Timestamp.from(filing.updatedAt()));
  }

  @Override
  public void markFiled(
      UUID filingId,
      Instant filedAt,
      String referenceNumber,
      String notes,
      UUID markedBy,
      Instant now) {
    jdbc.update(
        """
        UPDATE tax_filing
        SET status = 'FILED', filed_at = ?, reference_number = ?, notes = ?,
            marked_by = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(filedAt),
        referenceNumber,
        notes,
        markedBy,
        Timestamp.from(now),
        filingId);
  }

  @Override
  public void appendGeneratedFile(UUID filingId, String fileJsonObject, Instant now) {
    jdbc.update(
        """
        UPDATE tax_filing
        SET generated_files = COALESCE(generated_files, '[]'::jsonb) || ?::jsonb,
            updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        "[" + fileJsonObject + "]",
        Timestamp.from(now),
        filingId);
  }

  @Override
  public void markOverduePending(LocalDate today, Instant now) {
    jdbc.update(
        """
        UPDATE tax_filing
        SET status = 'OVERDUE', updated_at = ?
        WHERE deleted_at IS NULL AND status = 'PENDING' AND due_date < ?
        """,
        Timestamp.from(now),
        java.sql.Date.valueOf(today));
  }

  @Override
  public Optional<TcsRegisterRecord> findTcs(UUID pharmacyId, String month) {
    List<TcsRegisterRecord> rows =
        jdbc.query(
            """
            SELECT * FROM tcs_register
            WHERE pharmacy_id = ? AND month = ? AND deleted_at IS NULL
            """,
            this::mapTcs,
            pharmacyId,
            month);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void upsertTcsOnRelease(
      UUID pharmacyId,
      String month,
      String pharmacyName,
      String gstin,
      String pan,
      UUID settlementId,
      long gmvPaise,
      long tcsPaise,
      Instant now) {
    long cgst = tcsPaise / 2;
    long sgst = tcsPaise - cgst;
    Optional<TcsRegisterRecord> existing = findTcs(pharmacyId, month);
    if (existing.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO tcs_register (
            id, pharmacy_id, month, pharmacy_name, gstin, pan,
            gmv_paise, tcs_collected_paise, cgst_tcs_paise, sgst_tcs_paise,
            settlement_ids, created_at, updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?, ARRAY[?]::uuid[], ?, ?)
          """,
          Ids.newId(),
          pharmacyId,
          month,
          nullToEmpty(pharmacyName),
          nullToEmpty(gstin),
          nullToEmpty(pan),
          gmvPaise,
          tcsPaise,
          cgst,
          sgst,
          settlementId,
          Timestamp.from(now),
          Timestamp.from(now));
      return;
    }
    TcsRegisterRecord row = existing.get();
    if (row.settlementIds().contains(settlementId)) {
      return;
    }
    jdbc.update(
        """
        UPDATE tcs_register
        SET pharmacy_name = ?,
            gstin = ?,
            pan = ?,
            gmv_paise = gmv_paise + ?,
            tcs_collected_paise = tcs_collected_paise + ?,
            cgst_tcs_paise = cgst_tcs_paise + ?,
            sgst_tcs_paise = sgst_tcs_paise + ?,
            settlement_ids = array_append(settlement_ids, ?),
            updated_at = ?
        WHERE pharmacy_id = ? AND month = ? AND deleted_at IS NULL
        """,
        nullToEmpty(pharmacyName),
        nullToEmpty(gstin),
        nullToEmpty(pan),
        gmvPaise,
        tcsPaise,
        cgst,
        sgst,
        settlementId,
        Timestamp.from(now),
        pharmacyId,
        month);
  }

  @Override
  public TcsMonthTotals tcsTotals(String month) {
    try {
      return jdbc.queryForObject(
          """
          SELECT COALESCE(SUM(gmv_paise),0), COALESCE(SUM(tcs_collected_paise),0), COUNT(*)
          FROM tcs_register WHERE month = ? AND deleted_at IS NULL
          """,
          (rs, i) -> new TcsMonthTotals(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
          month);
    } catch (EmptyResultDataAccessException e) {
      return new TcsMonthTotals(0, 0, 0);
    }
  }

  @Override
  public TcsPage listTcs(String month, UUID pharmacyId, int limit, int offset) {
    StringBuilder where = new StringBuilder(" WHERE month = ? AND deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(month);
    if (pharmacyId != null) {
      where.append(" AND pharmacy_id = ? ");
      args.add(pharmacyId);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tcs_register" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<TcsRegisterRecord> rows =
        jdbc.query(
            "SELECT * FROM tcs_register"
                + where
                + " ORDER BY tcs_collected_paise DESC, pharmacy_id LIMIT ? OFFSET ?",
            this::mapTcs,
            pageArgs.toArray());
    return new TcsPage(rows, total == null ? 0L : total);
  }

  @Override
  public List<TcsRegisterRecord> listTcsAll(String month) {
    return jdbc.query(
        """
        SELECT * FROM tcs_register
        WHERE month = ? AND deleted_at IS NULL
        ORDER BY pharmacy_id
        """,
        this::mapTcs,
        month);
  }

  @Override
  public void linkTcsToFiling(String month, UUID filingId, Instant now) {
    jdbc.update(
        """
        UPDATE tcs_register
        SET gstr8_filing_id = ?, updated_at = ?
        WHERE month = ? AND deleted_at IS NULL
        """,
        filingId,
        Timestamp.from(now),
        month);
  }

  @Override
  public List<PharmacyCommissionRow> commissionByPharmacy(
      LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT s.pharmacy_id,
               COALESCE(p.pan_number, '') AS pan,
               COALESCE(SUM(s.commission_earned_paise), 0) AS commission_paise
        FROM settlement s
        LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
        WHERE s.deleted_at IS NULL
          AND s.status IN ('RELEASED', 'PAID')
          AND s.period_start >= ?
          AND s.period_start <= ?
        GROUP BY s.pharmacy_id, p.pan_number
        """,
        (rs, i) ->
            new PharmacyCommissionRow(
                (UUID) rs.getObject("pharmacy_id"),
                rs.getString("pan"),
                rs.getLong("commission_paise")),
        java.sql.Date.valueOf(fromInclusive),
        java.sql.Date.valueOf(toInclusive));
  }

  @Override
  public long totalCommissionPaise(LocalDate fromInclusive, LocalDate toInclusive) {
    Long v =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(commission_earned_paise), 0)
            FROM settlement
            WHERE deleted_at IS NULL
              AND status IN ('RELEASED', 'PAID')
              AND period_start >= ?
              AND period_start <= ?
            """,
            Long.class,
            java.sql.Date.valueOf(fromInclusive),
            java.sql.Date.valueOf(toInclusive));
    return v == null ? 0L : v;
  }

  @Override
  public long gatewayFeesPaise(Instant fromInclusive, Instant toExclusive) {
    Long v =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(gateway_fee_paise), 0)
            FROM payment
            WHERE status = 'CAPTURED'
              AND gateway_fee_paise IS NOT NULL
              AND created_at >= ?
              AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return v == null ? 0L : v;
  }

  private TaxFilingRecord mapFiling(ResultSet rs, int rowNum) throws SQLException {
    return new TaxFilingRecord(
        (UUID) rs.getObject("id"),
        rs.getString("filing_type"),
        rs.getString("period"),
        rs.getDate("due_date").toLocalDate(),
        rs.getString("status"),
        ts(rs, "filed_at"),
        rs.getString("reference_number"),
        rs.getString("notes"),
        (UUID) rs.getObject("marked_by"),
        rs.getString("generated_files"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private TcsRegisterRecord mapTcs(ResultSet rs, int rowNum) throws SQLException {
    List<UUID> ids = new ArrayList<>();
    Array arr = rs.getArray("settlement_ids");
    if (arr != null) {
      Object raw = arr.getArray();
      if (raw instanceof Object[] objs) {
        for (Object o : objs) {
          if (o instanceof UUID u) {
            ids.add(u);
          } else if (o != null) {
            ids.add(UUID.fromString(o.toString()));
          }
        }
      }
    }
    return new TcsRegisterRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("month"),
        rs.getString("pharmacy_name"),
        rs.getString("gstin"),
        rs.getString("pan"),
        rs.getLong("gmv_paise"),
        rs.getLong("tcs_collected_paise"),
        rs.getLong("cgst_tcs_paise"),
        rs.getLong("sgst_tcs_paise"),
        ids,
        (UUID) rs.getObject("gstr8_filing_id"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
