package com.nammamedmate.catalogue.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMedicineMappingStore implements MedicineMappingStore {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcMedicineMappingStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(MappingRow row) {
    jdbc.update(
        """
        INSERT INTO pharmacy_catalogue_mapping (
          id, pharmacy_id, master_medicine_id, pharmacy_price_paise, stock_quantity,
          is_visible, pause_hidden, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.masterMedicineId(),
        row.pharmacyPricePaise(),
        row.stockQuantity(),
        row.visible(),
        Timestamp.from(row.createdAt()),
        Timestamp.from(row.updatedAt()));
  }

  @Override
  public Optional<MappingRow> findById(UUID mappingId) {
    List<MappingRow> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, master_medicine_id, pharmacy_price_paise, stock_quantity,
                   is_visible, created_at, updated_at
            FROM pharmacy_catalogue_mapping WHERE id = ?
            """,
            this::mapMapping,
            mappingId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<MappingRow> findByPharmacyAndMedicine(UUID pharmacyId, UUID medicineId) {
    List<MappingRow> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, master_medicine_id, pharmacy_price_paise, stock_quantity,
                   is_visible, created_at, updated_at
            FROM pharmacy_catalogue_mapping
            WHERE pharmacy_id = ? AND master_medicine_id = ?
            """,
            this::mapMapping,
            pharmacyId,
            medicineId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public boolean exists(UUID pharmacyId, UUID medicineId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_catalogue_mapping
            WHERE pharmacy_id = ? AND master_medicine_id = ?
            """,
            Integer.class,
            pharmacyId,
            medicineId);
    return count != null && count > 0;
  }

  @Override
  public void update(
      UUID mappingId,
      Long pharmacyPricePaise,
      Integer stockQuantity,
      Boolean visible,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_catalogue_mapping
        SET pharmacy_price_paise = COALESCE(?, pharmacy_price_paise),
            stock_quantity = COALESCE(?, stock_quantity),
            is_visible = COALESCE(?, is_visible),
            updated_at = ?
        WHERE id = ?
        """,
        pharmacyPricePaise,
        stockQuantity,
        visible,
        Timestamp.from(updatedAt),
        mappingId);
  }

  @Override
  public void delete(UUID mappingId) {
    jdbc.update("DELETE FROM pharmacy_catalogue_mapping WHERE id = ?", mappingId);
  }

  @Override
  public ListResult listForPharmacy(PharmacyListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE pcm.pharmacy_id = ? ");
    List<Object> args = new ArrayList<>();
    args.add(filter.pharmacyId());
    if (filter.visible() != null) {
      where.append(" AND pcm.is_visible = ? ");
      args.add(filter.visible());
    }
    if (Boolean.TRUE.equals(filter.inStock())) {
      where.append(" AND pcm.stock_quantity > 0 ");
    } else if (Boolean.FALSE.equals(filter.inStock())) {
      where.append(" AND pcm.stock_quantity = 0 ");
    }
    if (filter.categoryId() != null) {
      where.append(" AND m.category_id = ? ");
      args.add(filter.categoryId());
    }
    if (filter.search() != null && !filter.search().isBlank()) {
      where.append(" AND (m.name ILIKE ? OR m.salt_composition ILIKE ?) ");
      String like = "%" + filter.search().trim() + "%";
      args.add(like);
      args.add(like);
    }

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacy_catalogue_mapping pcm"
                + " JOIN medicine_master m ON m.id = pcm.master_medicine_id"
                + where,
            Long.class,
            args.toArray());

    String sortCol =
        switch (filter.sort() == null ? "name" : filter.sort().toLowerCase(Locale.ROOT)) {
          case "pharmacy_price" -> "pcm.pharmacy_price_paise";
          case "stock_quantity" -> "pcm.stock_quantity";
          case "created_at" -> "pcm.created_at";
          default -> "m.name";
        };
    String ord = "desc".equalsIgnoreCase(filter.order()) ? "DESC" : "ASC";
    int offset = Math.max(0, (filter.page() - 1) * filter.limit());

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);

    List<MappingListRow> rows =
        jdbc.query(
            """
            SELECT pcm.id, pcm.master_medicine_id, m.name, m.salt_composition, m.manufacturer,
                   c.name AS category_name, m.form, m.pack_size, m.schedule, m.is_rx_only,
                   m.mrp_paise, m.mrp_ceiling_paise, pcm.pharmacy_price_paise, pcm.stock_quantity,
                   pcm.is_visible, pcm.created_at, pcm.updated_at
            FROM pharmacy_catalogue_mapping pcm
            JOIN medicine_master m ON m.id = pcm.master_medicine_id
            LEFT JOIN medicine_category c ON c.id = m.category_id
            """
                + where
                + " ORDER BY "
                + sortCol
                + " "
                + ord
                + " LIMIT ? OFFSET ?",
            this::mapListRow,
            pageArgs.toArray());
    return new ListResult(rows, total == null ? 0L : total);
  }

  @Override
  public AdminListResult listForAdmin(AdminListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE pcm.master_medicine_id = ? ");
    List<Object> args = new ArrayList<>();
    args.add(filter.masterMedicineId());
    if (filter.zoneId() != null) {
      where.append(" AND p.zone_id = ? ");
      args.add(filter.zoneId());
    }
    if (filter.visible() != null) {
      where.append(" AND pcm.is_visible = ? ");
      args.add(filter.visible());
    }
    if (filter.aboveCeilingOnly()) {
      where.append(
          " AND m.mrp_ceiling_paise IS NOT NULL AND pcm.pharmacy_price_paise > m.mrp_ceiling_paise ");
    }

    String from =
        """
        FROM pharmacy_catalogue_mapping pcm
        JOIN medicine_master m ON m.id = pcm.master_medicine_id
        JOIN pharmacies p ON p.id = pcm.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        """;

    Long total = jdbc.queryForObject("SELECT COUNT(*) " + from + where, Long.class, args.toArray());
    Long stocking =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pharmacy_catalogue_mapping WHERE master_medicine_id = ?",
            Long.class,
            filter.masterMedicineId());

    int offset = Math.max(0, (filter.page() - 1) * filter.limit());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);

    List<AdminMappingRow> rows =
        jdbc.query(
            """
            SELECT pcm.id, pcm.pharmacy_id,
                   COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS pharmacy_name,
                   z.name AS zone_name, pcm.pharmacy_price_paise, pcm.stock_quantity,
                   pcm.is_visible, pcm.created_at, m.mrp_ceiling_paise
            """
                + from
                + where
                + " ORDER BY pharmacy_name ASC LIMIT ? OFFSET ?",
            (rs, i) -> {
              Long ceiling = (Long) rs.getObject("mrp_ceiling_paise");
              long price = rs.getLong("pharmacy_price_paise");
              boolean above = ceiling != null && price > ceiling;
              return new AdminMappingRow(
                  (UUID) rs.getObject("id"),
                  (UUID) rs.getObject("pharmacy_id"),
                  rs.getString("pharmacy_name"),
                  rs.getString("zone_name"),
                  price,
                  rs.getInt("stock_quantity"),
                  rs.getBoolean("is_visible"),
                  above,
                  rs.getTimestamp("created_at").toInstant());
            },
            pageArgs.toArray());
    return new AdminListResult(rows, total == null ? 0L : total, stocking == null ? 0L : stocking);
  }

  @Override
  public Optional<MedicineRef> findMedicine(UUID medicineId) {
    List<MedicineRef> rows =
        jdbc.query(
            """
            SELECT id, name, mrp_paise, mrp_ceiling_paise, schedule, is_banned
            FROM medicine_master WHERE id = ?
            """,
            (rs, i) ->
                new MedicineRef(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    rs.getLong("mrp_paise"),
                    (Long) rs.getObject("mrp_ceiling_paise"),
                    rs.getString("schedule"),
                    rs.getBoolean("is_banned")),
            medicineId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<String> pharmacyStatus(UUID pharmacyId) {
    List<String> rows =
        jdbc.query(
            "SELECT status FROM pharmacies WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> rs.getString("status"),
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
  }

  @Override
  public int hideAllForMedicine(UUID medicineId) {
    Integer updated =
        jdbc.queryForObject(
            """
            WITH updated AS (
              UPDATE pharmacy_catalogue_mapping
              SET is_visible = FALSE, updated_at = NOW()
              WHERE master_medicine_id = ? AND is_visible = TRUE
              RETURNING 1
            )
            SELECT COUNT(*) FROM updated
            """,
            Integer.class,
            medicineId);
    return updated == null ? 0 : updated;
  }

  @Override
  public int hideAllForPharmacy(UUID pharmacyId) {
    Integer updated =
        jdbc.queryForObject(
            """
            WITH updated AS (
              UPDATE pharmacy_catalogue_mapping
              SET is_visible = FALSE, pause_hidden = TRUE, updated_at = NOW()
              WHERE pharmacy_id = ? AND is_visible = TRUE
              RETURNING 1
            )
            SELECT COUNT(*) FROM updated
            """,
            Integer.class,
            pharmacyId);
    return updated == null ? 0 : updated;
  }

  @Override
  public void restoreAllForPharmacy(UUID pharmacyId) {
    jdbc.update(
        """
        UPDATE pharmacy_catalogue_mapping
        SET is_visible = TRUE, pause_hidden = FALSE, updated_at = NOW()
        WHERE pharmacy_id = ? AND pause_hidden = TRUE
        """,
        pharmacyId);
  }

  @Override
  public CatalogueStats statsForPharmacy(UUID pharmacyId) {
    return jdbc.query(
        """
            SELECT
              COUNT(*)::int AS mapped,
              COUNT(*) FILTER (WHERE stock_quantity > 0)::int AS in_stock,
              COUNT(*) FILTER (WHERE stock_quantity = 0)::int AS oos
            FROM pharmacy_catalogue_mapping
            WHERE pharmacy_id = ?
            """,
        rs -> {
          if (!rs.next()) {
            return new CatalogueStats(0, 0, 0);
          }
          return new CatalogueStats(rs.getInt("mapped"), rs.getInt("in_stock"), rs.getInt("oos"));
        },
        pharmacyId);
  }

  @Override
  public void incrementMappedCount(UUID medicineId, int delta) {
    jdbc.update(
        """
        UPDATE medicine_master
        SET mapped_pharmacy_count = GREATEST(0, mapped_pharmacy_count + ?),
            updated_at = NOW()
        WHERE id = ?
        """,
        delta,
        medicineId);
  }

  @Override
  public void insertBulkJob(
      UUID jobId, List<UUID> pharmacyIds, Object payload, UUID initiatedBy, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO bulk_action_job (
            id, action, payload, pharmacy_ids, status, total_pharmacies,
            processed, succeeded, failed, skipped, skipped_pharmacies, result_payload,
            initiated_by, started_at, completed_at, created_at
        ) VALUES (
            ?, 'BULK_MAP'::bulk_action_type, ?::jsonb, ?, 'QUEUED'::bulk_job_status, ?,
            0, 0, 0, 0, '[]'::jsonb, '{}'::jsonb, ?, NULL, NULL, ?
        )
        """,
        jobId,
        writeJson(payload),
        pharmacyIds.toArray(UUID[]::new),
        pharmacyIds.size(),
        initiatedBy,
        Timestamp.from(createdAt));
  }

  @Override
  public Optional<BulkJobRow> findBulkJob(UUID jobId) {
    List<BulkJobRow> rows =
        jdbc.query(
            """
            SELECT id, action::text, status::text, pharmacy_ids, payload, initiated_by, created_at
            FROM bulk_action_job WHERE id = ?
            """,
            this::mapBulkJob,
            jobId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void markBulkJobRunning(UUID jobId, Instant startedAt) {
    jdbc.update(
        """
        UPDATE bulk_action_job
        SET status = 'RUNNING'::bulk_job_status, started_at = ?
        WHERE id = ? AND status = 'QUEUED'::bulk_job_status
        """,
        Timestamp.from(startedAt),
        jobId);
  }

  @Override
  public void markBulkJobCompleted(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Object> skippedPharmacies,
      Instant completedAt) {
    jdbc.update(
        """
        UPDATE bulk_action_job
        SET status = 'COMPLETED'::bulk_job_status,
            processed = ?, succeeded = ?, failed = ?, skipped = ?,
            skipped_pharmacies = ?::jsonb, completed_at = ?
        WHERE id = ?
        """,
        processed,
        succeeded,
        failed,
        skipped,
        writeJson(skippedPharmacies),
        Timestamp.from(completedAt),
        jobId);
  }

  @Override
  public List<BulkJobRow> findQueuedBulkMapJobs(int limit) {
    return jdbc.query(
        """
        SELECT id, action::text, status::text, pharmacy_ids, payload, initiated_by, created_at
        FROM bulk_action_job
        WHERE status = 'QUEUED' AND action = 'BULK_MAP'
        ORDER BY created_at ASC
        LIMIT ?
        """,
        this::mapBulkJob,
        limit);
  }

  private MappingRow mapMapping(ResultSet rs, int rowNum) throws SQLException {
    return new MappingRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("master_medicine_id"),
        rs.getLong("pharmacy_price_paise"),
        rs.getInt("stock_quantity"),
        rs.getBoolean("is_visible"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private MappingListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    return new MappingListRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("master_medicine_id"),
        rs.getString("name"),
        rs.getString("salt_composition"),
        rs.getString("manufacturer"),
        rs.getString("category_name"),
        rs.getString("form"),
        rs.getBigDecimal("pack_size"),
        rs.getString("schedule"),
        rs.getBoolean("is_rx_only"),
        rs.getLong("mrp_paise"),
        (Long) rs.getObject("mrp_ceiling_paise"),
        rs.getLong("pharmacy_price_paise"),
        rs.getInt("stock_quantity"),
        rs.getBoolean("is_visible"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private BulkJobRow mapBulkJob(ResultSet rs, int rowNum) throws SQLException {
    Array arr = rs.getArray("pharmacy_ids");
    List<UUID> ids = List.of();
    if (arr != null) {
      Object[] raw = (Object[]) arr.getArray();
      ids = Arrays.stream(raw).map(o -> (UUID) o).toList();
    }
    Object payload = readJson(rs.getString("payload"));
    return new BulkJobRow(
        (UUID) rs.getObject("id"),
        rs.getString("action"),
        rs.getString("status"),
        ids,
        payload,
        (UUID) rs.getObject("initiated_by"),
        rs.getTimestamp("created_at").toInstant());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize JSON", ex);
    }
  }

  private Object readJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse JSON", ex);
    }
  }
}
