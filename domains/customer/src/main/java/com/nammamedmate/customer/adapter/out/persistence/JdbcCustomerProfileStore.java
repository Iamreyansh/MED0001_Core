package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Component;

@Component
public class JdbcCustomerProfileStore implements CustomerProfileStore {

  private static final RowMapper<CustomerProfileRecord> ROW = JdbcCustomerProfileStore::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcCustomerProfileStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CustomerProfileRecord> findById(UUID id) {
    List<CustomerProfileRecord> rows =
        jdbc.query(
            """
            SELECT * FROM customers WHERE id = ? AND deleted_at IS NULL
            """,
            ROW,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public void lockCustomer(UUID id) {
    jdbc.query(
        """
        SELECT id FROM customers WHERE id = ? AND deleted_at IS NULL FOR UPDATE
        """,
        (rs, n) -> (UUID) rs.getObject("id"),
        id);
  }

  @Override
  public CustomerProfileRecord saveProfile(CustomerProfileRecord customer) {
    jdbc.update(
        """
        UPDATE customers SET
          name = ?, avatar_url = ?, date_of_birth = ?, gender = ?,
          preferred_language = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        customer.name(),
        customer.avatarUrl(),
        customer.dateOfBirth(),
        customer.gender(),
        customer.preferredLanguage(),
        Timestamp.from(customer.updatedAt()),
        customer.id());
    return customer;
  }

  @Override
  public void requestDeletion(UUID id, Instant requestedAt, String reason) {
    jdbc.update(
        """
        UPDATE customers SET deletion_requested_at = ?, deletion_reason = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(requestedAt),
        reason,
        Timestamp.from(requestedAt),
        id);
  }

  @Override
  public void cancelDeletion(UUID id) {
    jdbc.update(
        """
        UPDATE customers SET deletion_requested_at = NULL, deletion_reason = NULL, updated_at = NOW()
        WHERE id = ? AND deleted_at IS NULL
        """,
        id);
  }

  @Override
  public void flag(UUID id, String reason, String note, UUID flaggedBy, Instant flaggedAt) {
    jdbc.update(
        """
        UPDATE customers SET
          is_flagged = TRUE, flag_reason = ?, flag_note = ?, flagged_by = ?, flagged_at = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        reason,
        note,
        flaggedBy,
        Timestamp.from(flaggedAt),
        Timestamp.from(flaggedAt),
        id);
  }

  @Override
  public void unflag(UUID id) {
    jdbc.update(
        """
        UPDATE customers SET
          is_flagged = FALSE, flag_reason = NULL, flag_note = NULL,
          flagged_by = NULL, flagged_at = NULL, updated_at = NOW()
        WHERE id = ? AND deleted_at IS NULL
        """,
        id);
  }

  @Override
  public PageResult list(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    if (filter.search() != null) {
      where.append(" AND (name ILIKE ? ESCAPE '\\' OR phone ILIKE ? ESCAPE '\\') ");
      String q = "%" + escapeIlike(filter.search()) + "%";
      args.add(q);
      args.add(q);
    }
    if (filter.segment() != null) {
      where.append(" AND segment = ? ");
      args.add(filter.segment().toUpperCase());
    }
    if (filter.isFlagged() != null) {
      where.append(" AND is_flagged = ? ");
      args.add(filter.isFlagged());
    }
    if (filter.city() != null) {
      where.append(" AND LOWER(city) = LOWER(?) ");
      args.add(filter.city());
    }

    String sortCol =
        switch (filter.sort()) {
          case "name" -> "name";
          case "total_orders" -> "total_orders";
          case "total_ltv" -> "total_ltv_paise";
          default -> "created_at";
        };
    String order = "desc".equalsIgnoreCase(filter.order()) ? "DESC" : "ASC";

    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM customers" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(filter.offset());
    List<CustomerProfileRecord> items =
        jdbc.query(
            "SELECT * FROM customers"
                + where
                + " ORDER BY "
                + sortCol
                + " "
                + order
                + " NULLS LAST LIMIT ? OFFSET ?",
            ROW,
            pageArgs.toArray());
    return new PageResult(items, total == null ? 0L : total);
  }

  @Override
  public void updateSegment(UUID id, String segment) {
    jdbc.update("UPDATE customers SET segment = ?, updated_at = NOW() WHERE id = ?", segment, id);
  }

  @Override
  public void insertSegmentChange(
      UUID id,
      UUID customerId,
      String from,
      String to,
      int totalOrders,
      long totalLtvPaise,
      Instant changedAt) {
    jdbc.update(
        """
        INSERT INTO customer_segment_changes
          (id, customer_id, from_segment, to_segment, total_orders, total_ltv_paise, changed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        customerId,
        from,
        to,
        totalOrders,
        totalLtvPaise,
        Timestamp.from(changedAt));
  }

  @Override
  public List<CustomerProfileRecord> findAllActiveForSegmentRecompute() {
    return jdbc.query("SELECT * FROM customers WHERE deleted_at IS NULL", ROW);
  }

  @Override
  public List<CustomerProfileRecord> findDueForAnonymisation(Instant cutoff) {
    return jdbc.query(
        """
        SELECT * FROM customers
        WHERE deleted_at IS NULL
          AND deletion_requested_at IS NOT NULL
          AND deletion_requested_at <= ?
        """,
        ROW,
        Timestamp.from(cutoff));
  }

  @Override
  public void anonymise(UUID id, String hashedPhone, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE customers SET
          name = 'Deleted User',
          phone = ?,
          avatar_url = NULL,
          date_of_birth = NULL,
          gender = NULL,
          city = NULL,
          device_tokens = '{}',
          is_flagged = FALSE,
          flag_reason = NULL,
          flag_note = NULL,
          flagged_by = NULL,
          flagged_at = NULL,
          deletion_reason = NULL,
          deletion_requested_at = NULL,
          deleted_at = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        hashedPhone,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
    jdbc.update("UPDATE customers SET default_address_id = NULL WHERE id = ?", id);
    jdbc.update(
        """
        UPDATE customer_addresses SET
          flat_building = 'REDACTED',
          area_locality = 'REDACTED',
          city = 'REDACTED',
          state = 'REDACTED',
          pincode = '000000',
          latitude = 0,
          longitude = 0,
          is_default = FALSE,
          deleted_at = COALESCE(deleted_at, ?),
          updated_at = ?
        WHERE customer_id = ?
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
    jdbc.update(
        """
        UPDATE saved_payment_methods SET
          nickname = NULL,
          upi_id = CASE WHEN type = 'UPI' THEN 'REDACTED' ELSE NULL END,
          upi_handle = CASE WHEN type = 'UPI' THEN 'REDACTED' ELSE NULL END,
          razorpay_token_id = CASE WHEN type = 'CARD' THEN 'REDACTED' ELSE NULL END,
          card_last4 = CASE WHEN type = 'CARD' THEN '0000' ELSE NULL END,
          is_default = FALSE,
          deleted_at = COALESCE(deleted_at, ?)
        WHERE customer_id = ?
        """,
        Timestamp.from(deletedAt),
        id);
    jdbc.update(
        """
        UPDATE prescription SET
          patient_name = 'REDACTED',
          doctor_name = 'REDACTED',
          notes = NULL,
          medicines_extracted = NULL,
          updated_at = ?
        WHERE customer_id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        id);
    jdbc.update(
        """
        UPDATE consults SET
          patient_name = 'REDACTED',
          patient_phone = '0000000000',
          symptoms = NULL,
          feedback_text = NULL,
          updated_at = ?
        WHERE customer_id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        id);
    jdbc.update(
        """
        UPDATE support_tickets SET
          subject = 'REDACTED',
          resolution_summary = NULL,
          csat_feedback = NULL
        WHERE customer_id = ? AND deleted_at IS NULL
        """,
        id);
    jdbc.update(
        """
        UPDATE schedule_medicine SET
          medicine_name = 'REDACTED',
          prescribed_by = NULL,
          notes = NULL,
          condition_name = NULL,
          is_active = FALSE,
          updated_at = ?
        WHERE customer_id = ?
        """,
        Timestamp.from(deletedAt),
        id);
    jdbc.update(
        """
        UPDATE customers SET
          preferred_language = 'en',
          segment = NULL,
          wallet_balance_paise = 0,
          loyalty_points = 0,
          total_orders = 0,
          total_ltv_paise = 0,
          cancel_rate = 0,
          dispute_count = 0,
          last_order_at = NULL
        WHERE id = ?
        """,
        id);
    jdbc.update(
        """
        UPDATE wallet_transaction SET
          description = 'REDACTED',
          reference_id = NULL
        WHERE customer_id = ?
        """,
        id);
    jdbc.update("DELETE FROM referral_events WHERE referee_id = ? OR referrer_id = ?", id, id);
  }

  static String escapeIlike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @Override
  public int countNotificationsSince(UUID customerId, Instant since) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM customer_admin_notifications
            WHERE customer_id = ? AND created_at >= ?
            """,
            Integer.class,
            customerId,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  @Override
  public UUID insertNotification(
      UUID id,
      UUID customerId,
      String channel,
      String title,
      String body,
      String deepLink,
      UUID createdBy,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO customer_admin_notifications
          (id, customer_id, channel, title, body, deep_link, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        customerId,
        channel,
        title,
        body,
        deepLink,
        createdBy,
        Timestamp.from(createdAt));
    return id;
  }

  private static CustomerProfileRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CustomerProfileRecord(
        (UUID) rs.getObject("id"),
        rs.getString("phone"),
        rs.getString("name"),
        rs.getString("avatar_url"),
        rs.getObject("date_of_birth", LocalDate.class),
        rs.getString("gender"),
        rs.getString("preferred_language"),
        rs.getString("segment"),
        rs.getString("city"),
        rs.getBoolean("is_flagged"),
        rs.getString("flag_reason"),
        rs.getString("flag_note"),
        (UUID) rs.getObject("flagged_by"),
        toInstant(rs.getTimestamp("flagged_at")),
        rs.getLong("wallet_balance_paise"),
        rs.getInt("loyalty_points"),
        rs.getInt("total_orders"),
        rs.getLong("total_ltv_paise"),
        rs.getBigDecimal("cancel_rate") == null ? BigDecimal.ZERO : rs.getBigDecimal("cancel_rate"),
        rs.getInt("dispute_count"),
        toInstant(rs.getTimestamp("last_order_at")),
        toInstant(rs.getTimestamp("deletion_requested_at")),
        rs.getString("deletion_reason"),
        toInstant(rs.getTimestamp("created_at")),
        toInstant(rs.getTimestamp("updated_at")),
        toInstant(rs.getTimestamp("deleted_at")));
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
