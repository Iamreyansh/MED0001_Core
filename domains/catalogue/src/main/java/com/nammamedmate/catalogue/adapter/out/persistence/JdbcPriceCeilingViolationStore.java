package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPriceCeilingViolationStore implements PriceCeilingViolationStore {

  private final JdbcTemplate jdbc;

  public JdbcPriceCeilingViolationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertOpen(
      UUID id,
      UUID medicineId,
      UUID pharmacyId,
      long ceilingPaise,
      long pharmacyPricePaise,
      Instant detectedAt) {
    long overage = pharmacyPricePaise - ceilingPaise;
    jdbc.update(
        """
        INSERT INTO price_ceiling_violation (
          id, medicine_id, pharmacy_id, ceiling_price_paise, pharmacy_price_paise,
          overage_amount_paise, status, detected_at, last_notified_at, resolved_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, NULL, NULL)
        ON CONFLICT (medicine_id, pharmacy_id) DO UPDATE SET
          ceiling_price_paise = EXCLUDED.ceiling_price_paise,
          pharmacy_price_paise = EXCLUDED.pharmacy_price_paise,
          overage_amount_paise = EXCLUDED.overage_amount_paise,
          status = CASE
            WHEN price_ceiling_violation.status = 'RESOLVED' THEN 'OPEN'
            ELSE price_ceiling_violation.status
          END,
          detected_at = CASE
            WHEN price_ceiling_violation.status = 'RESOLVED' THEN EXCLUDED.detected_at
            ELSE price_ceiling_violation.detected_at
          END,
          resolved_at = CASE
            WHEN price_ceiling_violation.status = 'RESOLVED' THEN NULL
            ELSE price_ceiling_violation.resolved_at
          END
        """,
        id,
        medicineId,
        pharmacyId,
        ceilingPaise,
        pharmacyPricePaise,
        overage,
        Timestamp.from(detectedAt));
  }

  @Override
  public int resolveOpenForMedicine(UUID medicineId, Instant resolvedAt) {
    return jdbc.update(
        """
        UPDATE price_ceiling_violation
        SET status = 'RESOLVED', resolved_at = ?
        WHERE medicine_id = ? AND status IN ('OPEN', 'NOTIFIED')
        """,
        Timestamp.from(resolvedAt),
        medicineId);
  }

  @Override
  public void resolveStale(Instant resolvedAt) {
    jdbc.update(
        """
        UPDATE price_ceiling_violation v
        SET status = 'RESOLVED', resolved_at = ?
        WHERE v.status IN ('OPEN', 'NOTIFIED')
          AND NOT EXISTS (
            SELECT 1
            FROM pharmacy_catalogue_mapping pcm
            JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
            WHERE pcm.pharmacy_id = v.pharmacy_id
              AND pcm.master_medicine_id = v.medicine_id
              AND mm.mrp_ceiling_paise IS NOT NULL
              AND pcm.pharmacy_price_paise > mm.mrp_ceiling_paise
              AND pcm.is_visible = TRUE
          )
        """,
        Timestamp.from(resolvedAt));
  }

  @Override
  public ViolationListResult list(UUID medicineId, UUID zoneId, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE v.status IN ('OPEN', 'NOTIFIED') ");
    List<Object> args = new ArrayList<>();
    if (medicineId != null) {
      where.append(" AND v.medicine_id = ? ");
      args.add(medicineId);
    }
    if (zoneId != null) {
      where.append(" AND p.zone_id = ? ");
      args.add(zoneId);
    }
    String from =
        """
        FROM price_ceiling_violation v
        JOIN medicine_master m ON m.id = v.medicine_id
        JOIN pharmacies p ON p.id = v.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        """;
    Long total = jdbc.queryForObject("SELECT COUNT(*) " + from + where, Long.class, args.toArray());
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);

    List<ViolationRow> rows =
        jdbc.query(
            """
            SELECT v.id, v.medicine_id, m.name AS medicine_name, v.ceiling_price_paise,
                   v.pharmacy_id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS pharmacy_name,
                   v.pharmacy_price_paise, v.overage_amount_paise, z.name AS zone_name,
                   v.detected_at, v.last_notified_at, v.status
            """
                + from
                + where
                + " ORDER BY v.detected_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
              Timestamp detected = rs.getTimestamp("detected_at");
              Timestamp notified = rs.getTimestamp("last_notified_at");
              return new ViolationRow(
                  (UUID) rs.getObject("id"),
                  (UUID) rs.getObject("medicine_id"),
                  rs.getString("medicine_name"),
                  rs.getLong("ceiling_price_paise"),
                  (UUID) rs.getObject("pharmacy_id"),
                  rs.getString("pharmacy_name"),
                  rs.getLong("pharmacy_price_paise"),
                  rs.getLong("overage_amount_paise"),
                  rs.getString("zone_name"),
                  detected == null ? null : detected.toInstant(),
                  notified == null ? null : notified.toInstant(),
                  rs.getString("status"));
            },
            pageArgs.toArray());
    return new ViolationListResult(rows, total == null ? 0L : total);
  }

  @Override
  public List<OpenViolation> listOpen(UUID medicineId) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT v.id, v.medicine_id, m.name AS medicine_name, v.pharmacy_id,
                   v.ceiling_price_paise, v.pharmacy_price_paise
            FROM price_ceiling_violation v
            JOIN medicine_master m ON m.id = v.medicine_id
            WHERE v.status IN ('OPEN', 'NOTIFIED')
            """);
    List<Object> args = new ArrayList<>();
    if (medicineId != null) {
      sql.append(" AND v.medicine_id = ? ");
      args.add(medicineId);
    }
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new OpenViolation(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("medicine_id"),
                rs.getString("medicine_name"),
                (UUID) rs.getObject("pharmacy_id"),
                rs.getLong("ceiling_price_paise"),
                rs.getLong("pharmacy_price_paise")),
        args.toArray());
  }

  @Override
  public void markNotified(List<UUID> violationIds, Instant notifiedAt) {
    if (violationIds == null || violationIds.isEmpty()) {
      return;
    }
    for (UUID id : violationIds) {
      jdbc.update(
          """
          UPDATE price_ceiling_violation
          SET status = 'NOTIFIED', last_notified_at = ?
          WHERE id = ? AND status IN ('OPEN', 'NOTIFIED')
          """,
          Timestamp.from(notifiedAt),
          id);
    }
  }
}
