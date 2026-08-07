package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMedicineStore implements MedicineStore {

  private static final String SELECT_BASE =
      """
      SELECT m.id, m.name, m.salt_composition, m.manufacturer, m.category_id,
             c.name AS category_name, m.form, m.pack_size, m.pack_unit, m.schedule,
             m.hsn_code, m.gst_pct, m.mrp_paise, m.mrp_ceiling_paise, m.is_rx_only,
             m.is_banned, m.ban_reason, m.monthly_demand, m.mapped_pharmacy_count,
             m.substitutes, m.description, m.created_by, m.created_at, m.updated_at
      FROM medicine_master m
      LEFT JOIN medicine_category c ON c.id = m.category_id
      """;

  private final JdbcTemplate jdbc;

  public JdbcMedicineStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(MedicineRow row) {
    Array subs =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Array>)
                connection ->
                    connection.createArrayOf("uuid", row.substitutes().toArray(UUID[]::new)));
    jdbc.update(
        """
        INSERT INTO medicine_master (
          id, name, salt_composition, manufacturer, category_id, form, pack_size,
          pack_unit, schedule, hsn_code, gst_pct, mrp_paise, mrp_ceiling_paise,
          is_rx_only, is_banned, ban_reason, monthly_demand, mapped_pharmacy_count,
          substitutes, description, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.name(),
        row.saltComposition(),
        row.manufacturer(),
        row.categoryId(),
        row.form(),
        row.packSize(),
        row.packUnit(),
        row.schedule(),
        row.hsnCode(),
        row.gstPct(),
        row.mrpPaise(),
        row.mrpCeilingPaise(),
        row.rxOnly(),
        row.banned(),
        row.banReason(),
        row.monthlyDemand(),
        row.mappedPharmacyCount(),
        subs,
        row.description(),
        row.createdBy(),
        Timestamp.from(row.createdAt()),
        Timestamp.from(row.updatedAt()));
  }

  @Override
  public void update(
      UUID id,
      String name,
      String description,
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Long mrpPaise,
      Boolean rxOnly,
      List<UUID> substitutes,
      Instant updatedAt) {
    StringBuilder sql = new StringBuilder("UPDATE medicine_master SET updated_at = ?");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(updatedAt));
    if (name != null) {
      sql.append(", name = ?");
      args.add(name);
    }
    if (description != null) {
      sql.append(", description = ?");
      args.add(description);
    }
    if (categoryId != null) {
      sql.append(", category_id = ?");
      args.add(categoryId);
    }
    if (schedule != null) {
      sql.append(", schedule = ?");
      args.add(schedule);
    }
    if (gstPct != null) {
      sql.append(", gst_pct = ?");
      args.add(gstPct);
    }
    if (mrpPaise != null) {
      sql.append(", mrp_paise = ?");
      args.add(mrpPaise);
    }
    if (rxOnly != null) {
      sql.append(", is_rx_only = ?");
      args.add(rxOnly);
    }
    if (substitutes != null) {
      sql.append(", substitutes = ?");
      args.add(toUuidArray(substitutes));
    }
    sql.append(" WHERE id = ?");
    args.add(id);
    jdbc.update(sql.toString(), args.toArray());
  }

  @Override
  public void setBanned(UUID id, boolean banned, String banReason, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE medicine_master
        SET is_banned = ?, ban_reason = ?, updated_at = ?
        WHERE id = ?
        """,
        banned,
        banReason,
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public Optional<MedicineRow> findById(UUID id) {
    List<MedicineRow> rows = jdbc.query(SELECT_BASE + " WHERE m.id = ?", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public ListResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
    List<Object> args = new ArrayList<>();
    if (filter.bannedOnly()) {
      where.append(" AND m.is_banned = TRUE ");
    } else {
      where.append(" AND m.is_banned = FALSE ");
    }
    if (filter.categoryId() != null) {
      where.append(" AND m.category_id = ? ");
      args.add(filter.categoryId());
    }
    if (filter.schedule() != null) {
      where.append(" AND m.schedule = ? ");
      args.add(filter.schedule());
    }
    if (filter.gstPct() != null) {
      where.append(" AND m.gst_pct = ? ");
      args.add(filter.gstPct());
    }
    if (filter.rxOnly() != null) {
      where.append(" AND m.is_rx_only = ? ");
      args.add(filter.rxOnly());
    }
    if (filter.search() != null && !filter.search().isBlank()) {
      where.append(
          """
           AND (
             m.search_tsv @@ plainto_tsquery('english', ?)
             OR m.name ILIKE '%' || ? || '%'
             OR m.salt_composition ILIKE '%' || ? || '%'
             OR m.manufacturer ILIKE '%' || ? || '%'
             OR m.hsn_code ILIKE '%' || ? || '%'
           )
          """);
      String q = filter.search().trim();
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
    }

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_master m " + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;

    String orderBy = orderByClause(filter.sort(), filter.order());
    int offset = Math.max(0, (filter.page() - 1) * filter.limit());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);

    List<MedicineRow> rows =
        jdbc.query(
            SELECT_BASE + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
            this::mapRow,
            pageArgs.toArray());
    return new ListResult(rows, totalCount);
  }

  @Override
  public SummaryStats summary(Instant asOf) {
    return jdbc.query(
        """
            SELECT
              COUNT(*) AS total_skus,
              COUNT(DISTINCT category_id) AS category_count,
              COUNT(*) FILTER (WHERE is_rx_only) AS rx_only_count,
              COUNT(*) FILTER (WHERE NOT is_rx_only) AS otc_count,
              COUNT(*) FILTER (WHERE is_banned) AS banned_count,
              COUNT(*) FILTER (WHERE schedule = 'H') AS schedule_h_count,
              COUNT(*) FILTER (WHERE schedule = 'H1') AS schedule_h1_count,
              COUNT(*) FILTER (WHERE schedule = 'X') AS schedule_x_count,
              AVG(mrp_paise)::bigint AS avg_mrp_paise,
              COALESCE(SUM(mapped_pharmacy_count), 0) AS total_pharmacy_mappings
            FROM medicine_master
            """,
        rs -> {
          if (!rs.next()) {
            return new SummaryStats(0, 0, 0, 0, 0, 0, 0, 0, null, 0, asOf);
          }
          long avg = rs.getLong("avg_mrp_paise");
          Long avgPaise = rs.wasNull() ? null : avg;
          return new SummaryStats(
              rs.getLong("total_skus"),
              rs.getLong("category_count"),
              rs.getLong("rx_only_count"),
              rs.getLong("otc_count"),
              rs.getLong("banned_count"),
              rs.getLong("schedule_h_count"),
              rs.getLong("schedule_h1_count"),
              rs.getLong("schedule_x_count"),
              avgPaise,
              rs.getLong("total_pharmacy_mappings"),
              asOf);
        });
  }

  @Override
  public boolean hsnExists(String hsnCode) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM hsn_reference WHERE hsn_code = ?", Integer.class, hsnCode);
    return count != null && count > 0;
  }

  @Override
  public boolean categoryActive(UUID categoryId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM medicine_category
            WHERE id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            categoryId);
    return count != null && count > 0;
  }

  @Override
  public int countExistingIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM medicine_master WHERE id IN (" + placeholders + ")",
            Integer.class,
            ids.toArray());
    return count == null ? 0 : count;
  }

  @Override
  public List<SubstituteRef> findSubstituteRefs(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    return jdbc.query(
        "SELECT id, name, manufacturer FROM medicine_master WHERE id IN (" + placeholders + ")",
        (rs, rowNum) ->
            new SubstituteRef(
                (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("manufacturer")),
        ids.toArray());
  }

  @Override
  public List<UUID> listAllIds() {
    return jdbc.query("SELECT id FROM medicine_master", (rs, rowNum) -> (UUID) rs.getObject("id"));
  }

  @Override
  public void updateMonthlyDemand(UUID id, int monthlyDemand, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE medicine_master
        SET monthly_demand = ?, updated_at = ?
        WHERE id = ?
        """,
        monthlyDemand,
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public int countActiveByCategoryId(UUID categoryId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM medicine_master
            WHERE category_id = ? AND is_banned = FALSE
            """,
            Integer.class,
            categoryId);
    return count == null ? 0 : count;
  }

  private Object toUuidArray(List<UUID> substitutes) {
    return jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Array>)
            connection -> connection.createArrayOf("uuid", substitutes.toArray(UUID[]::new)));
  }

  private static String orderByClause(String sort, String order) {
    String dir = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
    String col =
        switch (sort == null ? "name" : sort.toLowerCase(Locale.ROOT)) {
          case "monthly_demand" -> "m.monthly_demand";
          case "mapped_pharmacy_count" -> "m.mapped_pharmacy_count";
          case "mrp" -> "m.mrp_paise";
          case "created_at" -> "m.created_at";
          default -> "m.name";
        };
    return col + " " + dir + ", m.id ASC";
  }

  private MedicineRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    long ceiling = rs.getLong("mrp_ceiling_paise");
    Long ceilingPaise = rs.wasNull() ? null : ceiling;
    return new MedicineRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("salt_composition"),
        rs.getString("manufacturer"),
        (UUID) rs.getObject("category_id"),
        rs.getString("category_name"),
        rs.getString("form"),
        rs.getBigDecimal("pack_size"),
        rs.getString("pack_unit"),
        rs.getString("schedule"),
        rs.getString("hsn_code") == null ? null : rs.getString("hsn_code").trim(),
        rs.getInt("gst_pct"),
        rs.getLong("mrp_paise"),
        ceilingPaise,
        rs.getBoolean("is_rx_only"),
        rs.getBoolean("is_banned"),
        rs.getString("ban_reason"),
        rs.getInt("monthly_demand"),
        rs.getInt("mapped_pharmacy_count"),
        readUuidArray(rs.getArray("substitutes")),
        rs.getString("description"),
        (UUID) rs.getObject("created_by"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }

  private static List<UUID> readUuidArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof UUID[] uuids) {
      return Arrays.asList(uuids);
    }
    if (raw instanceof Object[] objs) {
      List<UUID> out = new ArrayList<>(objs.length);
      for (Object o : objs) {
        if (o instanceof UUID u) {
          out.add(u);
        } else if (o != null) {
          out.add(UUID.fromString(o.toString()));
        }
      }
      return out;
    }
    return List.of();
  }
}
