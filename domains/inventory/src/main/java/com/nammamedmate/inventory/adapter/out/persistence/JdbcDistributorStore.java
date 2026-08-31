package com.nammamedmate.inventory.adapter.out.persistence;

import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.domain.Distributor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDistributorStore implements DistributorStore {

  private static final String SELECT =
      """
      SELECT id, pharmacy_id, firm_name, contact_name, phone, email, gstin,
             drug_licence_number, address, payment_terms_days, credit_limit_paise,
             is_active, created_at, updated_at, deleted_at
        FROM distributors
      """;

  private final JdbcTemplate jdbc;

  public JdbcDistributorStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Distributor> findById(UUID pharmacyId, UUID distributorId) {
    List<Distributor> rows =
        jdbc.query(
            SELECT + " WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL",
            ROW_MAPPER,
            pharmacyId,
            distributorId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Distributor> findByIdIncludingDeleted(UUID pharmacyId, UUID distributorId) {
    List<Distributor> rows =
        jdbc.query(
            SELECT + " WHERE pharmacy_id = ? AND id = ?", ROW_MAPPER, pharmacyId, distributorId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Distributor> findActiveByPhone(UUID pharmacyId, String phone, UUID excludeId) {
    String sql =
        SELECT + " WHERE pharmacy_id = ? AND phone = ? AND is_active = TRUE AND deleted_at IS NULL";
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    args.add(phone);
    if (excludeId != null) {
      sql += " AND id <> ?";
      args.add(excludeId);
    }
    List<Distributor> rows = jdbc.query(sql, ROW_MAPPER, args.toArray());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Distributor> findActiveSystem(UUID pharmacyId) {
    List<Distributor> rows =
        jdbc.query(
            SELECT + " WHERE pharmacy_id = ? AND is_system = TRUE AND deleted_at IS NULL",
            ROW_MAPPER,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean isSystem(UUID pharmacyId, UUID distributorId) {
    Boolean flag =
        jdbc.queryForObject(
            """
            SELECT is_system FROM distributors
             WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
            """,
            Boolean.class,
            pharmacyId,
            distributorId);
    return Boolean.TRUE.equals(flag);
  }

  @Override
  public Distributor insert(Distributor distributor) {
    return insert(distributor, false);
  }

  @Override
  public Distributor insertSystem(Distributor distributor) {
    return insert(distributor, true);
  }

  private Distributor insert(Distributor distributor, boolean system) {
    jdbc.update(
        """
        INSERT INTO distributors (
          id, pharmacy_id, firm_name, contact_name, phone, email, gstin,
          drug_licence_number, address, payment_terms_days, credit_limit_paise,
          is_active, is_system, created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        distributor.id(),
        distributor.pharmacyId(),
        distributor.firmName(),
        distributor.contactName(),
        distributor.phone(),
        distributor.email(),
        distributor.gstin(),
        distributor.drugLicenceNumber(),
        distributor.address(),
        distributor.paymentTermsDays(),
        distributor.creditLimitPaise(),
        distributor.active(),
        system,
        Timestamp.from(distributor.createdAt()),
        Timestamp.from(distributor.updatedAt()),
        distributor.deletedAt() == null ? null : Timestamp.from(distributor.deletedAt()));
    return distributor;
  }

  @Override
  public Distributor update(Distributor distributor) {
    jdbc.update(
        """
        UPDATE distributors SET
          firm_name = ?, contact_name = ?, phone = ?, email = ?, gstin = ?,
          drug_licence_number = ?, address = ?, payment_terms_days = ?,
          credit_limit_paise = ?, is_active = ?, updated_at = ?
        WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
        """,
        distributor.firmName(),
        distributor.contactName(),
        distributor.phone(),
        distributor.email(),
        distributor.gstin(),
        distributor.drugLicenceNumber(),
        distributor.address(),
        distributor.paymentTermsDays(),
        distributor.creditLimitPaise(),
        distributor.active(),
        Timestamp.from(distributor.updatedAt()),
        distributor.pharmacyId(),
        distributor.id());
    return distributor;
  }

  @Override
  public void deactivate(UUID pharmacyId, UUID distributorId, Instant now) {
    jdbc.update(
        """
        UPDATE distributors SET is_active = FALSE, updated_at = ?
        WHERE pharmacy_id = ? AND id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(now),
        pharmacyId,
        distributorId);
  }

  @Override
  public ListResult list(UUID pharmacyId, Boolean active, String q, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE pharmacy_id = ? AND deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (active != null) {
      where.append(" AND is_active = ?");
      args.add(active);
    }
    if (q != null && !q.isBlank()) {
      where.append(
          """
           AND (
             firm_name ILIKE ? OR contact_name ILIKE ? OR phone ILIKE ? OR gstin ILIKE ?
           )
          """);
      String like = "%" + q.trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM distributors" + where, Long.class, args.toArray());
    long count = total == null ? 0L : total;
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<Distributor> items =
        jdbc.query(
            SELECT + where + " ORDER BY firm_name ASC LIMIT ? OFFSET ?",
            ROW_MAPPER,
            pageArgs.toArray());
    return new ListResult(items, count);
  }

  @Override
  public KpiRow kpi(UUID pharmacyId) {
    Long distributors =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM distributors
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND is_active = TRUE
            """,
            Long.class,
            pharmacyId);
    Long products =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT product_id) FROM distributor_supply_item
            WHERE pharmacy_id = ?
            """,
            Long.class,
            pharmacyId);
    Long payable =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(i.line_total_paise), 0)
              FROM purchase_grn g
              JOIN purchase_grn_item i ON i.grn_id = g.id
             WHERE g.pharmacy_id = ?
               AND g.status = 'STOCKED'
               AND g.deleted_at IS NULL
            """,
            Long.class,
            pharmacyId);
    Long onCredit =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM distributors
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND is_active = TRUE
              AND payment_terms_days > 0
            """,
            Long.class,
            pharmacyId);
    return new KpiRow(
        distributors == null ? 0L : distributors,
        products == null ? 0L : products,
        payable == null ? 0L : payable,
        onCredit == null ? 0L : onCredit);
  }

  @Override
  public long outstandingPayablePaise(UUID pharmacyId, UUID distributorId) {
    // ponytail: repayments not modelled yet — outstanding = Σ STOCKED GRN totals (repayments=0)
    Long sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(i.line_total_paise), 0)
              FROM purchase_grn g
              JOIN purchase_grn_item i ON i.grn_id = g.id
             WHERE g.pharmacy_id = ?
               AND g.distributor_id = ?
               AND g.status = 'STOCKED'
               AND g.deleted_at IS NULL
            """,
            Long.class,
            pharmacyId,
            distributorId);
    return sum == null ? 0L : sum;
  }

  @Override
  public LocalDate lastPurchaseDate(UUID pharmacyId, UUID distributorId) {
    return jdbc.query(
        """
            SELECT MAX(invoice_date) FROM purchase_grn
             WHERE pharmacy_id = ? AND distributor_id = ?
               AND status = 'STOCKED' AND deleted_at IS NULL
            """,
        rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null,
        pharmacyId,
        distributorId);
  }

  private static final RowMapper<Distributor> ROW_MAPPER = (rs, i) -> mapRow(rs);

  static Distributor mapRow(ResultSet rs) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new Distributor(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("firm_name"),
        rs.getString("contact_name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("gstin"),
        rs.getString("drug_licence_number"),
        rs.getString("address"),
        rs.getInt("payment_terms_days"),
        rs.getLong("credit_limit_paise"),
        rs.getBoolean("is_active"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
