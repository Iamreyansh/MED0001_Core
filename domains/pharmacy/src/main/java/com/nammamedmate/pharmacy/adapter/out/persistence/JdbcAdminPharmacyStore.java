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
  private static final BigDecimal ZERO = new BigDecimal("0.00");

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminPharmacyStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public PageResult list(ListFilter filter) {
    Where where = buildWhere(filter);
    String orderSql = orderBy(filter);

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacy_directory_view v " + where.sql,
            Long.class,
            where.args.toArray());
    long totalCount = total == null ? 0L : total;

    List<Object> pageArgs = new ArrayList<>(where.args);
    pageArgs.add(filter.limit());
    pageArgs.add(filter.offset());

    List<AdminListRow> rows =
        jdbc.query(
            """
            SELECT v.*,
                   (
                     SELECT j.overall_status FROM auto_kyc_jobs j
                     WHERE j.pharmacy_id = v.pharmacy_id
                     ORDER BY j.triggered_at DESC LIMIT 1
                   ) AS auto_kyc_status
            FROM pharmacy_directory_view v
            """
                + where.sql
                + " ORDER BY "
                + orderSql
                + " LIMIT ? OFFSET ?",
            this::mapListRow,
            pageArgs.toArray());
    return new PageResult(rows, totalCount);
  }

  @Override
  public List<AdminListRow> exportRows(ListFilter filter) {
    Where where = buildWhere(filter);
    String orderSql = orderBy(filter);
    List<Object> args = new ArrayList<>(where.args);
    args.add(filter.limit());
    return jdbc.query(
        """
        SELECT v.*,
               (
                 SELECT j.overall_status FROM auto_kyc_jobs j
                 WHERE j.pharmacy_id = v.pharmacy_id
                 ORDER BY j.triggered_at DESC LIMIT 1
               ) AS auto_kyc_status
        FROM pharmacy_directory_view v
        """
            + where.sql
            + " ORDER BY "
            + orderSql
            + " LIMIT ?",
        this::mapListRow,
        args.toArray());
  }

  @Override
  public DirectorySummary directorySummary(Instant asOf) {
    Map<String, Object> counts =
        jdbc.queryForMap(
            """
            SELECT
              COUNT(*) FILTER (WHERE status = 'ACTIVE') AS total_active,
              COUNT(*) FILTER (WHERE status = 'PENDING_KYC') AS pending_kyc,
              COUNT(*) FILTER (WHERE status = 'KYC_SUBMITTED') AS kyc_submitted,
              COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
              COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected,
              COUNT(*) FILTER (WHERE is_online = TRUE) AS currently_online,
              COALESCE(SUM(orders_today), 0) AS orders_today,
              COALESCE(SUM(gmv_today_paise), 0) AS gmv_today_paise,
              COALESCE(SUM(commission_today_paise), 0) AS commission_today_paise,
              COALESCE(SUM(net_payout_paise), 0) AS payout_due_paise
            FROM pharmacy_directory_view
            WHERE deleted_at IS NULL
            """);
    return new DirectorySummary(
        ((Number) counts.get("total_active")).longValue(),
        ((Number) counts.get("pending_kyc")).longValue(),
        ((Number) counts.get("kyc_submitted")).longValue(),
        ((Number) counts.get("suspended")).longValue(),
        ((Number) counts.get("rejected")).longValue(),
        ((Number) counts.get("currently_online")).longValue(),
        ((Number) counts.get("orders_today")).longValue(),
        ((Number) counts.get("gmv_today_paise")).longValue(),
        ((Number) counts.get("commission_today_paise")).longValue(),
        ((Number) counts.get("payout_due_paise")).longValue(),
        asOf);
  }

  @Override
  public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
    List<AdminDetailRow> rows =
        jdbc.query(
            """
            SELECT p.*, z.name AS zone_name
            FROM pharmacies p
            LEFT JOIN zones z ON z.id = p.zone_id
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

  @Override
  public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacies SET commission_pct = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        commissionPct,
        Timestamp.from(updatedAt),
        pharmacyId);
  }

  private static Where buildWhere(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE v.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();

    if (filter.status() != null && !"ALL".equalsIgnoreCase(filter.status())) {
      where.append(" AND v.status = ? ");
      args.add(filter.status());
    }
    if (filter.zoneId() != null) {
      where.append(" AND v.zone_id = ? ");
      args.add(filter.zoneId());
    }
    if (filter.plan() != null && !filter.plan().isBlank()) {
      where.append(" AND v.plan = ? ");
      args.add(filter.plan());
    }
    if (filter.online() != null) {
      where.append(" AND v.is_online = ? ");
      args.add(filter.online());
    }
    if (filter.search() != null && !filter.search().isBlank()) {
      String q = filter.search().trim();
      where.append(
          """
           AND (
             COALESCE(v.business_name, '') ILIKE ?
             OR COALESCE(v.owner_name, '') ILIKE ?
             OR COALESCE(v.phone, '') ILIKE ?
             OR COALESCE(v.code, '') ILIKE ?
             OR similarity(COALESCE(v.business_name, ''), ?) > 0.15
             OR similarity(COALESCE(v.owner_name, ''), ?) > 0.15
             OR similarity(COALESCE(v.phone, ''), ?) > 0.15
             OR similarity(COALESCE(v.code, ''), ?) > 0.15
           )
          """);
      String like = "%" + q + "%";
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
    }
    return new Where(where.toString(), args);
  }

  private static String orderBy(ListFilter filter) {
    String sortCol =
        switch (filter.sort() == null ? "created_at" : filter.sort()) {
          case "submitted_at" -> "v.kyc_submitted_at";
          case "business_name" -> "v.business_name";
          case "gmv_today" -> "v.gmv_today_paise";
          case "orders_today" -> "v.orders_today";
          case "rating" -> "v.rating";
          case "fill_rate" -> "v.fill_rate_pct";
          default -> "v.created_at";
        };
    String order = "asc".equalsIgnoreCase(filter.order()) ? "ASC" : "DESC";
    return sortCol + " " + order + " NULLS LAST, v.pharmacy_id ASC";
  }

  private AdminListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    Instant submitted = ts(rs, "kyc_submitted_at");
    Instant slaReset = ts(rs, "kyc_sla_reset_at");
    Instant ageAnchor = slaReset != null ? slaReset : submitted;
    BigDecimal rating = rs.getBigDecimal("rating");
    BigDecimal fill = rs.getBigDecimal("fill_rate_pct");
    BigDecimal commission = rs.getBigDecimal("commission_pct");
    return new AdminListRow(
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("code"),
        rs.getString("business_name"),
        rs.getString("owner_name"),
        rs.getString("phone"),
        rs.getString("email"),
        (UUID) rs.getObject("zone_id"),
        rs.getString("zone_name"),
        rs.getString("status"),
        rs.getString("plan"),
        rs.getBoolean("is_online"),
        submitted,
        ts(rs, "created_at"),
        ageAnchor,
        rs.getString("auto_kyc_status"),
        rating == null ? ZERO : rating,
        rs.getInt("review_count"),
        rs.getInt("orders_today"),
        rs.getLong("gmv_today_paise"),
        fill == null ? ZERO : fill,
        commission == null ? new BigDecimal("8.00") : commission,
        rs.getLong("net_payout_paise"),
        ts(rs, "metrics_as_of"));
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
        rs.getString("zone_name"),
        rs.getBoolean("is_online"),
        rs.getBoolean("can_reapply"),
        ts(rs, "kyc_submitted_at"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"),
        ts(rs, "plan_expires_at"),
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

  @Override
  public List<AdminListRow> listByIds(List<UUID> pharmacyIds) {
    if (pharmacyIds == null || pharmacyIds.isEmpty()) {
      return List.of();
    }
    String placeholders =
        pharmacyIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
    List<Object> args = new ArrayList<>(pharmacyIds);
    return jdbc.query(
        """
        SELECT v.*,
               (
                 SELECT j.overall_status FROM auto_kyc_jobs j
                 WHERE j.pharmacy_id = v.pharmacy_id
                 ORDER BY j.triggered_at DESC LIMIT 1
               ) AS auto_kyc_status
        FROM pharmacy_directory_view v
        WHERE v.pharmacy_id IN ("""
            + placeholders
            + ") ORDER BY v.business_name",
        this::mapListRow,
        args.toArray());
  }

  @Override
  public List<UUID> listActivePharmacyIds() {
    return jdbc.query(
        """
        SELECT id FROM pharmacies
        WHERE status = 'ACTIVE' AND deleted_at IS NULL
        ORDER BY created_at
        """,
        (rs, rowNum) -> (UUID) rs.getObject("id"));
  }

  private record Where(String sql, List<Object> args) {}
}
