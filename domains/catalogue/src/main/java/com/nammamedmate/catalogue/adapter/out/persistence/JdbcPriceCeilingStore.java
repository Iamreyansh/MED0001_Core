package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPriceCeilingStore implements PriceCeilingStore {

  private final JdbcTemplate jdbc;

  public JdbcPriceCeilingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void setCeiling(
      UUID medicineId,
      long ceilingPaise,
      LocalDate effectiveFrom,
      String reason,
      UUID setById,
      String setByName,
      String setByRole,
      Instant setAt) {
    jdbc.update(
        """
        UPDATE medicine_master
        SET mrp_ceiling_paise = ?,
            mrp_ceiling_effective_from = ?,
            mrp_ceiling_reason = ?,
            mrp_ceiling_set_by = ?,
            mrp_ceiling_set_by_name = ?,
            mrp_ceiling_set_by_role = ?,
            mrp_ceiling_set_at = ?,
            updated_at = ?
        WHERE id = ?
        """,
        ceilingPaise,
        Date.valueOf(effectiveFrom),
        reason,
        setById,
        setByName,
        setByRole,
        Timestamp.from(setAt),
        Timestamp.from(setAt),
        medicineId);
  }

  @Override
  public void clearCeiling(UUID medicineId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE medicine_master
        SET mrp_ceiling_paise = NULL,
            mrp_ceiling_effective_from = NULL,
            mrp_ceiling_reason = NULL,
            mrp_ceiling_set_by = NULL,
            mrp_ceiling_set_by_name = NULL,
            mrp_ceiling_set_by_role = NULL,
            mrp_ceiling_set_at = NULL,
            updated_at = ?
        WHERE id = ?
        """,
        Timestamp.from(updatedAt),
        medicineId);
  }

  @Override
  public CeilingListResult listCeilings(
      UUID categoryId, Boolean hasViolations, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE m.mrp_ceiling_paise IS NOT NULL ");
    List<Object> args = new ArrayList<>();
    if (categoryId != null) {
      where.append(" AND m.category_id = ? ");
      args.add(categoryId);
    }
    if (Boolean.TRUE.equals(hasViolations)) {
      where.append(
          """
           AND EXISTS (
            SELECT 1 FROM price_ceiling_violation v
            WHERE v.medicine_id = m.id AND v.status IN ('OPEN', 'NOTIFIED')
          )
          """);
    } else if (Boolean.FALSE.equals(hasViolations)) {
      where.append(
          """
           AND NOT EXISTS (
            SELECT 1 FROM price_ceiling_violation v
            WHERE v.medicine_id = m.id AND v.status IN ('OPEN', 'NOTIFIED')
          )
          """);
    }

    String from =
        """
        FROM medicine_master m
        LEFT JOIN medicine_category c ON c.id = m.category_id
        """;
    Long total = jdbc.queryForObject("SELECT COUNT(*) " + from + where, Long.class, args.toArray());
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);

    List<CeilingRow> rows =
        jdbc.query(
            """
            SELECT m.id, m.name, c.name AS category_name, m.schedule, m.mrp_paise,
                   m.mrp_ceiling_paise, m.mrp_ceiling_effective_from, m.mrp_ceiling_reason,
                   m.mrp_ceiling_set_by, m.mrp_ceiling_set_by_name, m.mrp_ceiling_set_by_role,
                   m.mrp_ceiling_set_at,
                   (SELECT COUNT(*) FROM price_ceiling_violation v
                     WHERE v.medicine_id = m.id AND v.status IN ('OPEN', 'NOTIFIED')) AS above_cnt
            """
                + from
                + where
                + " ORDER BY m.name ASC LIMIT ? OFFSET ?",
            (rs, i) -> {
              Date eff = rs.getDate("mrp_ceiling_effective_from");
              Timestamp setAt = rs.getTimestamp("mrp_ceiling_set_at");
              return new CeilingRow(
                  (UUID) rs.getObject("id"),
                  rs.getString("name"),
                  rs.getString("category_name"),
                  rs.getString("schedule"),
                  rs.getLong("mrp_paise"),
                  rs.getLong("mrp_ceiling_paise"),
                  rs.getLong("above_cnt"),
                  eff == null ? null : eff.toLocalDate(),
                  (UUID) rs.getObject("mrp_ceiling_set_by"),
                  rs.getString("mrp_ceiling_set_by_name"),
                  rs.getString("mrp_ceiling_set_by_role"),
                  setAt == null ? null : setAt.toInstant(),
                  rs.getString("mrp_ceiling_reason"));
            },
            pageArgs.toArray());
    return new CeilingListResult(rows, total == null ? 0L : total);
  }

  @Override
  public Optional<String> findAdminName(UUID adminId) {
    if (adminId == null) {
      return Optional.empty();
    }
    List<String> names =
        jdbc.query(
            "SELECT name FROM admin_staff WHERE id = ?", (rs, i) -> rs.getString("name"), adminId);
    return names.isEmpty() ? Optional.empty() : Optional.ofNullable(names.getFirst());
  }

  @Override
  public List<AboveCeilingMapping> findAboveCeilingMappings(UUID medicineId) {
    return jdbc.query(
        """
        SELECT pcm.pharmacy_id, pcm.master_medicine_id, pcm.pharmacy_price_paise, mm.mrp_ceiling_paise
        FROM pharmacy_catalogue_mapping pcm
        JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
        WHERE pcm.master_medicine_id = ?
          AND mm.mrp_ceiling_paise IS NOT NULL
          AND pcm.pharmacy_price_paise > mm.mrp_ceiling_paise
          AND pcm.is_visible = TRUE
        """,
        (rs, i) ->
            new AboveCeilingMapping(
                (UUID) rs.getObject("pharmacy_id"),
                (UUID) rs.getObject("master_medicine_id"),
                rs.getLong("pharmacy_price_paise"),
                rs.getLong("mrp_ceiling_paise")),
        medicineId);
  }

  @Override
  public List<AboveCeilingMapping> findAllAboveCeilingMappings() {
    return jdbc.query(
        """
        SELECT pcm.pharmacy_id, pcm.master_medicine_id, pcm.pharmacy_price_paise, mm.mrp_ceiling_paise
        FROM pharmacy_catalogue_mapping pcm
        JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
        WHERE mm.mrp_ceiling_paise IS NOT NULL
          AND pcm.pharmacy_price_paise > mm.mrp_ceiling_paise
          AND pcm.is_visible = TRUE
        """,
        (rs, i) ->
            new AboveCeilingMapping(
                (UUID) rs.getObject("pharmacy_id"),
                (UUID) rs.getObject("master_medicine_id"),
                rs.getLong("pharmacy_price_paise"),
                rs.getLong("mrp_ceiling_paise")));
  }

  @Override
  public long countAboveCeiling(UUID medicineId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM pharmacy_catalogue_mapping pcm
            JOIN medicine_master mm ON mm.id = pcm.master_medicine_id
            WHERE pcm.master_medicine_id = ?
              AND mm.mrp_ceiling_paise IS NOT NULL
              AND pcm.pharmacy_price_paise > mm.mrp_ceiling_paise
              AND pcm.is_visible = TRUE
            """,
            Long.class,
            medicineId);
    return count == null ? 0L : count;
  }
}
