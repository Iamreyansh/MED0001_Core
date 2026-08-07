package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMedicineSearchStore implements MedicineSearchStore {

  private final JdbcTemplate jdbc;

  public JdbcMedicineSearchStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SearchPage search(
      String query,
      UUID categoryId,
      String schedule,
      Boolean rxOnly,
      boolean excludeBanned,
      int page,
      int limit) {
    String tsQuery = toTsQuery(query);
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (excludeBanned) {
      where.append(" AND m.is_banned = FALSE ");
    }
    if (categoryId != null) {
      where.append(" AND m.category_id = ? ");
      args.add(categoryId);
    }
    if (schedule != null && !schedule.isBlank()) {
      where.append(" AND m.schedule = ? ");
      args.add(schedule.trim().toUpperCase());
    }
    if (rxOnly != null) {
      where.append(" AND m.is_rx_only = ? ");
      args.add(rxOnly);
    }
    where.append(
        """
         AND (
           m.search_tsv @@ to_tsquery('english', ?)
           OR similarity(m.name, ?) > 0.2
           OR similarity(m.salt_composition, ?) > 0.2
           OR m.name ILIKE ?
         )
        """);
    args.add(tsQuery);
    args.add(query);
    args.add(query);
    args.add("%" + query + "%");

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_master m" + where, Long.class, args.toArray());

    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(tsQuery);
    pageArgs.add(query);
    pageArgs.add(limit);
    pageArgs.add(offset);

    List<SearchHit> rows =
        jdbc.query(
            """
            SELECT m.id, m.name, m.salt_composition, m.manufacturer,
                   c.name AS category_name, c.slug AS category_slug,
                   m.form, m.pack_size, m.pack_unit, m.schedule, m.is_rx_only, m.mrp_paise,
                   (
                     ts_rank(m.search_tsv, to_tsquery('english', ?))
                     + GREATEST(similarity(m.name, ?), 0)
                   ) AS relevance_score
            FROM medicine_master m
            LEFT JOIN medicine_category c ON c.id = m.category_id
            """
                + where
                + " ORDER BY relevance_score DESC, m.name ASC LIMIT ? OFFSET ?",
            this::mapSearchHit,
            pageArgs.toArray());
    return new SearchPage(rows, total == null ? 0L : total);
  }

  @Override
  public List<AutocompleteHit> autocomplete(String query, int limit) {
    return jdbc.query(
        """
        SELECT id, name, manufacturer
        FROM medicine_master
        WHERE is_banned = FALSE
          AND name ILIKE ?
        ORDER BY similarity(name, ?) DESC, name ASC
        LIMIT ?
        """,
        (rs, i) ->
            new AutocompleteHit(
                (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("manufacturer")),
        query + "%",
        query,
        limit);
  }

  @Override
  public Optional<String> didYouMean(String query) {
    List<String> rows =
        jdbc.query(
            """
            SELECT name FROM medicine_master
            WHERE is_banned = FALSE AND similarity(name, ?) > 0.4
            ORDER BY similarity(name, ?) DESC
            LIMIT 1
            """,
            (rs, i) -> rs.getString("name"),
            query,
            query);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
  }

  @Override
  public List<StockOffer> bestOffers(
      List<UUID> medicineIds, UUID zoneId, UUID pharmacyId, boolean showOos) {
    if (medicineIds == null || medicineIds.isEmpty()) {
      return List.of();
    }
    String stockClause = showOos ? "" : " AND pcm.stock_quantity > 0 ";
    return jdbc.query(
        """
        SELECT DISTINCT ON (pcm.master_medicine_id)
               pcm.master_medicine_id, pcm.pharmacy_id,
               COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS pharmacy_name,
               pcm.pharmacy_price_paise, pcm.stock_quantity
        FROM pharmacy_catalogue_mapping pcm
        JOIN pharmacies p ON p.id = pcm.pharmacy_id
        JOIN medicine_master m ON m.id = pcm.master_medicine_id
        WHERE pcm.master_medicine_id = ANY(?::uuid[])
          AND pcm.is_visible = TRUE
        """
            + stockClause
            + """
          AND p.status = 'ACTIVE'
          AND p.is_online = TRUE
          AND COALESCE(p.admin_forced_offline, FALSE) = FALSE
          AND p.deleted_at IS NULL
          AND (m.mrp_ceiling_paise IS NULL
               OR pcm.pharmacy_price_paise <= m.mrp_ceiling_paise)
          AND (?::uuid IS NULL OR p.zone_id = ?)
          AND (?::uuid IS NULL OR p.id = ?)
        ORDER BY pcm.master_medicine_id, pcm.pharmacy_price_paise ASC
        """,
        (rs, i) -> mapOffer(rs),
        uuidArrayLiteral(medicineIds),
        zoneId,
        zoneId,
        pharmacyId,
        pharmacyId);
  }

  @Override
  public List<StockOffer> stockingOffers(UUID medicineId, UUID zoneId, boolean showOos) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT pcm.master_medicine_id, pcm.pharmacy_id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS pharmacy_name,
                   pcm.pharmacy_price_paise, pcm.stock_quantity
            FROM pharmacy_catalogue_mapping pcm
            JOIN pharmacies p ON p.id = pcm.pharmacy_id
            JOIN medicine_master m ON m.id = pcm.master_medicine_id
            WHERE pcm.master_medicine_id = ?
              AND pcm.is_visible = TRUE
            """);
    if (!showOos) {
      sql.append(" AND pcm.stock_quantity > 0 ");
    }
    sql.append(
        """
              AND p.status = 'ACTIVE'
              AND p.is_online = TRUE
              AND COALESCE(p.admin_forced_offline, FALSE) = FALSE
              AND p.deleted_at IS NULL
              AND (m.mrp_ceiling_paise IS NULL
                   OR pcm.pharmacy_price_paise <= m.mrp_ceiling_paise)
            """);
    List<Object> args = new ArrayList<>();
    args.add(medicineId);
    if (zoneId != null) {
      sql.append(" AND p.zone_id = ? ");
      args.add(zoneId);
    }
    sql.append(" ORDER BY pcm.pharmacy_price_paise ASC ");
    return jdbc.query(sql.toString(), (rs, i) -> mapOffer(rs), args.toArray());
  }

  @Override
  public List<SubstituteHit> findSubstitutes(List<UUID> substituteIds) {
    if (substituteIds == null || substituteIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        """
        SELECT id, name, salt_composition, manufacturer, form, pack_size,
               schedule, is_rx_only, mrp_paise
        FROM medicine_master
        WHERE id = ANY(?::uuid[]) AND is_banned = FALSE
        ORDER BY name ASC
        """,
        (rs, i) ->
            new SubstituteHit(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getString("salt_composition"),
                rs.getString("manufacturer"),
                rs.getString("form"),
                rs.getBigDecimal("pack_size"),
                rs.getString("schedule"),
                rs.getBoolean("is_rx_only"),
                rs.getLong("mrp_paise")),
        uuidArrayLiteral(substituteIds));
  }

  @Override
  public List<AvailabilityHit> checkAvailability(UUID pharmacyId, List<UUID> medicineIds) {
    if (medicineIds == null || medicineIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        """
        SELECT m.id, m.name, m.is_rx_only,
               COALESCE(pcm.stock_quantity, 0) AS stock_quantity,
               pcm.pharmacy_price_paise
        FROM medicine_master m
        LEFT JOIN pharmacy_catalogue_mapping pcm
          ON pcm.master_medicine_id = m.id AND pcm.pharmacy_id = ?
        WHERE m.id = ANY(?::uuid[])
        ORDER BY m.name ASC
        """,
        (rs, i) -> {
          Long price = (Long) rs.getObject("pharmacy_price_paise");
          int stock = rs.getInt("stock_quantity");
          return new AvailabilityHit(
              (UUID) rs.getObject("id"),
              rs.getString("name"),
              stock > 0,
              stock,
              price,
              rs.getBoolean("is_rx_only"));
        },
        pharmacyId,
        uuidArrayLiteral(medicineIds));
  }

  @Override
  public PharmacyMasterPage searchMasterForPharmacy(
      UUID pharmacyId, String query, boolean inStockOnly, int page, int limit) {
    String tsQuery = toTsQuery(query);
    StringBuilder where =
        new StringBuilder(
            """
             WHERE m.is_banned = FALSE
               AND (
                 m.search_tsv @@ to_tsquery('english', ?)
                 OR similarity(m.name, ?) > 0.2
                 OR m.name ILIKE ?
               )
            """);
    List<Object> args = new ArrayList<>();
    args.add(tsQuery);
    args.add(query);
    args.add("%" + query + "%");
    if (inStockOnly) {
      where.append(" AND pcm.stock_quantity IS NOT NULL AND pcm.stock_quantity > 0 ");
    }

    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM medicine_master m
            LEFT JOIN pharmacy_catalogue_mapping pcm
              ON pcm.master_medicine_id = m.id AND pcm.pharmacy_id = ?
            """
                + where,
            Long.class,
            prepend(pharmacyId, args).toArray());

    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>();
    pageArgs.add(pharmacyId);
    pageArgs.addAll(args);
    pageArgs.add(limit);
    pageArgs.add(offset);

    List<PharmacyMasterHit> rows =
        jdbc.query(
            """
            SELECT m.id, m.name, m.salt_composition, m.manufacturer, m.form, m.pack_size,
                   m.schedule, m.is_rx_only, m.mrp_paise,
                   pcm.pharmacy_price_paise, pcm.stock_quantity, pcm.id AS mapping_id,
                   pcm.is_visible
            FROM medicine_master m
            LEFT JOIN pharmacy_catalogue_mapping pcm
              ON pcm.master_medicine_id = m.id AND pcm.pharmacy_id = ?
            """
                + where
                + " ORDER BY m.name ASC LIMIT ? OFFSET ?",
            (rs, i) -> {
              UUID mappingId = (UUID) rs.getObject("mapping_id");
              Long price = (Long) rs.getObject("pharmacy_price_paise");
              Integer stock =
                  rs.getObject("stock_quantity") == null ? null : rs.getInt("stock_quantity");
              Boolean visible =
                  rs.getObject("is_visible") == null ? null : rs.getBoolean("is_visible");
              return new PharmacyMasterHit(
                  (UUID) rs.getObject("id"),
                  rs.getString("name"),
                  rs.getString("salt_composition"),
                  rs.getString("manufacturer"),
                  rs.getString("form"),
                  rs.getBigDecimal("pack_size"),
                  rs.getString("schedule"),
                  rs.getBoolean("is_rx_only"),
                  rs.getLong("mrp_paise"),
                  price,
                  stock,
                  mappingId,
                  mappingId != null,
                  Boolean.TRUE.equals(visible));
            },
            pageArgs.toArray());
    return new PharmacyMasterPage(rows, total == null ? 0L : total);
  }

  private SearchHit mapSearchHit(ResultSet rs, int i) throws SQLException {
    return new SearchHit(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("salt_composition"),
        rs.getString("manufacturer"),
        rs.getString("category_name"),
        rs.getString("category_slug"),
        rs.getString("form"),
        rs.getBigDecimal("pack_size"),
        rs.getString("pack_unit"),
        rs.getString("schedule"),
        rs.getBoolean("is_rx_only"),
        rs.getLong("mrp_paise"),
        rs.getDouble("relevance_score"));
  }

  private static StockOffer mapOffer(ResultSet rs) throws SQLException {
    int stock = rs.getInt("stock_quantity");
    return new StockOffer(
        (UUID) rs.getObject("master_medicine_id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("pharmacy_name"),
        rs.getLong("pharmacy_price_paise"),
        stock,
        stock > 0);
  }

  private static List<Object> prepend(Object first, List<Object> rest) {
    List<Object> out = new ArrayList<>();
    out.add(first);
    out.addAll(rest);
    return out;
  }

  static String uuidArrayLiteral(List<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  /** Build a tolerant tsquery from free text (words OR'd; empty → match-nothing token). */
  static String toTsQuery(String query) {
    if (query == null || query.isBlank()) {
      return "nomatch";
    }
    String[] parts = query.trim().toLowerCase().split("\\s+");
    List<String> terms = new ArrayList<>();
    for (String part : parts) {
      String cleaned = part.replaceAll("[^a-z0-9]", "");
      if (!cleaned.isEmpty()) {
        terms.add(cleaned + ":*");
      }
    }
    if (terms.isEmpty()) {
      return "nomatch";
    }
    return String.join(" | ", terms);
  }
}
