package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAdminPharmacyStore implements AdminPharmacyStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminPharmacyStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public PageResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();

    if (filter.status() != null && !"ALL".equalsIgnoreCase(filter.status())) {
      where.append(" AND p.status = ? ");
      args.add(filter.status());
    }
    if (filter.zoneId() != null) {
      where.append(" AND p.zone_id = ? ");
      args.add(filter.zoneId());
    }
    if (filter.plan() != null && !filter.plan().isBlank()) {
      where.append(" AND p.plan = ? ");
      args.add(filter.plan());
    }
    if (filter.online() != null) {
      where.append(" AND p.is_online = ? ");
      args.add(filter.online());
    }
    if (filter.search() != null && !filter.search().isBlank()) {
      where.append(
          """
           AND (
             p.business_name ILIKE ? OR p.name ILIKE ? OR p.owner_name ILIKE ?
             OR p.phone ILIKE ? OR p.code ILIKE ?
           )
          """);
      String like = "%" + filter.search().trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
    }

    String sortCol =
        switch (filter.sort() == null ? "created_at" : filter.sort()) {
          case "submitted_at" -> "p.kyc_submitted_at";
          case "business_name" -> "COALESCE(p.business_name, p.name)";
          default -> "p.created_at";
        };
    String order = "asc".equalsIgnoreCase(filter.order()) ? "ASC" : "DESC";
    // NULLs last for submitted_at asc queue
    String orderSql = sortCol + " " + order + " NULLS LAST, p.id ASC";

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacies p " + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(filter.offset());

    List<AdminListRow> rows =
        jdbc.query(
            """
            SELECT p.id, p.code, p.business_name, p.name, p.owner_name, p.phone, p.status, p.plan,
                   p.is_online, p.kyc_submitted_at, p.kyc_sla_reset_at, p.created_at,
                   z.name AS zone_name,
                   (
                     SELECT j.overall_status FROM auto_kyc_jobs j
                     WHERE j.pharmacy_id = p.id
                     ORDER BY j.triggered_at DESC LIMIT 1
                   ) AS auto_kyc_status
            FROM pharmacies p
            LEFT JOIN zones z ON z.id = p.zone_id
            """
                + where
                + " ORDER BY "
                + orderSql
                + " LIMIT ? OFFSET ?",
            this::mapListRow,
            pageArgs.toArray());
    return new PageResult(rows, totalCount);
  }

  @Override
  public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
    List<AdminDetailRow> rows =
        jdbc.query(
            """
            SELECT p.* FROM pharmacies p
            WHERE p.id = ? AND p.deleted_at IS NULL
            """,
            this::mapDetailRow,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Map<String, String> documentStatusSummary(UUID pharmacyId) {
    List<Map.Entry<String, String>> entries =
        jdbc.query(
            """
            SELECT document_type, status FROM kyc_documents
            WHERE pharmacy_id = ? AND deleted_at IS NULL
            ORDER BY created_at ASC
            """,
            (rs, i) -> Map.entry(rs.getString("document_type"), rs.getString("status")),
            pharmacyId);
    Map<String, String> summary = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : entries) {
      summary.put(e.getKey(), e.getValue());
    }
    return summary;
  }

  @Override
  public String nextCode() {
    Long n = jdbc.queryForObject("SELECT nextval('pharmacy_code_seq')", Long.class);
    long seq = n == null ? 1L : n;
    return "PHM-" + String.format("%04d", seq);
  }

  @Override
  public void approve(
      UUID pharmacyId,
      BigDecimal commissionPct,
      UUID zoneId,
      Instant activatedAt,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          status = 'ACTIVE',
          is_online = TRUE,
          commission_pct = ?,
          zone_id = ?,
          activated_at = ?,
          rejection_reason = NULL,
          rejection_details = NULL,
          suspended_at = NULL,
          suspend_type = NULL,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        commissionPct,
        zoneId,
        Timestamp.from(activatedAt),
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  @Override
  public void reject(
      UUID pharmacyId,
      String rejectionReason,
      String rejectionDetails,
      boolean canReapply,
      Instant rejectedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          status = 'REJECTED',
          is_online = FALSE,
          can_reapply = ?,
          rejection_reason = ?,
          rejection_details = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        canReapply,
        rejectionReason,
        rejectionDetails,
        Timestamp.from(rejectedAt),
        pharmacyId);
  }

  @Override
  public void suspend(
      UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          status = 'SUSPENDED',
          is_online = FALSE,
          suspend_type = ?,
          can_reapply = ?,
          suspended_at = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        suspendType,
        canReapply,
        Timestamp.from(suspendedAt),
        Timestamp.from(suspendedAt),
        pharmacyId);
  }

  @Override
  public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {
    jdbc.update(
        """
        UPDATE pharmacies SET
          status = 'ACTIVE',
          is_online = TRUE,
          suspend_type = NULL,
          suspended_at = NULL,
          can_reapply = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        canReapply,
        Timestamp.from(reactivatedAt),
        pharmacyId);
  }

  @Override
  public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET kyc_sla_reset_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(slaResetAt),
        Timestamp.from(slaResetAt),
        pharmacyId);
  }

  private AdminListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    String businessName = rs.getString("business_name");
    if (businessName == null || businessName.isBlank()) {
      businessName = rs.getString("name");
    }
    Instant submitted = ts(rs, "kyc_submitted_at");
    Instant slaReset = ts(rs, "kyc_sla_reset_at");
    Instant ageAnchor = slaReset != null ? slaReset : submitted;
    return new AdminListRow(
        (UUID) rs.getObject("id"),
        rs.getString("code"),
        businessName,
        rs.getString("owner_name"),
        rs.getString("phone"),
        rs.getString("zone_name"),
        rs.getString("status"),
        rs.getString("plan"),
        rs.getBoolean("is_online"),
        submitted,
        ts(rs, "created_at"),
        ageAnchor,
        rs.getString("auto_kyc_status"));
  }

  private AdminDetailRow mapDetailRow(ResultSet rs, int rowNum) throws SQLException {
    String businessName = rs.getString("business_name");
    if (businessName == null || businessName.isBlank()) {
      businessName = rs.getString("name");
    }
    return new AdminDetailRow(
        (UUID) rs.getObject("id"),
        rs.getString("code"),
        businessName,
        rs.getString("owner_name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("business_type"),
        readJson(rs.getString("address")),
        rs.getString("gstin"),
        rs.getString("drug_licence_number"),
        rs.getString("fssai_number"),
        rs.getString("pan_number"),
        rs.getString("status"),
        rs.getString("plan"),
        rs.getBigDecimal("commission_pct") == null
            ? new BigDecimal("8.00")
            : rs.getBigDecimal("commission_pct"),
        (UUID) rs.getObject("zone_id"),
        rs.getBoolean("is_online"),
        rs.getBoolean("can_reapply"),
        ts(rs, "kyc_submitted_at"),
        ts(rs, "created_at"),
        rs.getString("rejection_reason"),
        rs.getString("rejection_details"),
        ts(rs, "activated_at"),
        ts(rs, "suspended_at"),
        rs.getString("suspend_type"),
        ts(rs, "kyc_sla_reset_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private Map<String, Object> readJson(String json) {
    if (json == null || json.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
