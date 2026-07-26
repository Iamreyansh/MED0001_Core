package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.PaymentMethodStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcPaymentMethodStore implements PaymentMethodStore {

  private static final RowMapper<PaymentMethodRecord> ROW = JdbcPaymentMethodStore::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcPaymentMethodStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<PaymentMethodRecord> listByCustomer(UUID customerId) {
    return jdbc.query(
        """
        SELECT * FROM saved_payment_methods
        WHERE customer_id = ? AND deleted_at IS NULL
        ORDER BY is_default DESC, created_at ASC
        """,
        ROW,
        customerId);
  }

  @Override
  public int countByCustomerAndType(UUID customerId, String type) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM saved_payment_methods
            WHERE customer_id = ? AND type = ? AND deleted_at IS NULL
            """,
            Integer.class,
            customerId,
            type);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<PaymentMethodRecord> findByIdForCustomer(UUID methodId, UUID customerId) {
    List<PaymentMethodRecord> rows =
        jdbc.query(
            """
            SELECT * FROM saved_payment_methods
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            ROW,
            methodId,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public List<PaymentMethodRecord> listByCustomerAndType(UUID customerId, String type) {
    return jdbc.query(
        """
        SELECT * FROM saved_payment_methods
        WHERE customer_id = ? AND type = ? AND deleted_at IS NULL
        """,
        ROW,
        customerId,
        type);
  }

  @Override
  public Optional<PaymentMethodRecord> findByIdempotencyKey(String idempotencyKey) {
    List<PaymentMethodRecord> rows =
        jdbc.query(
            """
            SELECT * FROM saved_payment_methods
            WHERE idempotency_key = ? AND deleted_at IS NULL
            """,
            ROW,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public PaymentMethodRecord insert(PaymentMethodRecord method) {
    jdbc.update(
        """
        INSERT INTO saved_payment_methods (
          id, customer_id, type, is_default, nickname, upi_id, upi_handle,
          razorpay_token_id, card_last4, card_network, card_type, idempotency_key,
          created_at, deleted_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        method.id(),
        method.customerId(),
        method.type(),
        method.isDefault(),
        method.nickname(),
        method.upiIdEncrypted(),
        method.upiHandle(),
        method.razorpayTokenEncrypted(),
        method.cardLast4(),
        method.cardNetwork(),
        method.cardType(),
        method.idempotencyKey(),
        Timestamp.from(method.createdAt()));
    return method;
  }

  @Override
  public void softDelete(UUID methodId, UUID customerId, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE saved_payment_methods
        SET deleted_at = ?, is_default = FALSE
        WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        methodId,
        customerId);
  }

  @Override
  public void clearDefaultFlags(UUID customerId) {
    jdbc.update(
        """
        UPDATE saved_payment_methods SET is_default = FALSE
        WHERE customer_id = ? AND deleted_at IS NULL AND is_default = TRUE
        """,
        customerId);
  }

  @Override
  public void setDefault(UUID methodId, UUID customerId) {
    jdbc.update(
        """
        UPDATE saved_payment_methods SET is_default = TRUE
        WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
        """,
        methodId,
        customerId);
  }

  @Override
  public Optional<UUID> findDefaultMethodId(UUID customerId) {
    List<UUID> rows =
        jdbc.query(
            """
            SELECT id FROM saved_payment_methods
            WHERE customer_id = ? AND is_default = TRUE AND deleted_at IS NULL
            """,
            (rs, n) -> (UUID) rs.getObject("id"),
            customerId);
    return rows.stream().findFirst();
  }

  private static PaymentMethodRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    return new PaymentMethodRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("type"),
        rs.getBoolean("is_default"),
        rs.getString("nickname"),
        rs.getString("upi_id"),
        rs.getString("upi_handle"),
        rs.getString("razorpay_token_id"),
        rs.getString("card_last4"),
        rs.getString("card_network"),
        rs.getString("card_type"),
        rs.getString("idempotency_key"),
        rs.getTimestamp("created_at").toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
