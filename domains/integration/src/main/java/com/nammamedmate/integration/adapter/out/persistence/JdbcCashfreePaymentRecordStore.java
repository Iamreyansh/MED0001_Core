package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CashfreePaymentRecordStore;
import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcCashfreePaymentRecordStore implements CashfreePaymentRecordStore {

  private final JdbcTemplate jdbc;

  public JdbcCashfreePaymentRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<CashfreePaymentRecord> MAPPER =
      (rs, i) ->
          new CashfreePaymentRecord(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("platform_order_id"),
              rs.getString("cashfree_order_id"),
              rs.getString("cashfree_payment_id"),
              rs.getInt("amount_paise"),
              rs.getString("currency"),
              rs.getString("payment_method"),
              rs.getString("status"),
              instant(rs.getTimestamp("created_at")),
              instant(rs.getTimestamp("captured_at")));

  @Override
  public void insert(CashfreePaymentRecord record) {
    jdbc.update(
        """
        INSERT INTO cashfree_payment_records (
          id, platform_order_id, cashfree_order_id, cashfree_payment_id,
          amount_paise, currency, payment_method, status, created_at, captured_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.platformOrderId(),
        record.gatewayOrderId(),
        record.gatewayPaymentId(),
        record.amountPaise(),
        record.currency(),
        record.paymentMethod(),
        record.status(),
        Timestamp.from(record.createdAt()),
        record.capturedAt() == null ? null : Timestamp.from(record.capturedAt()));
  }

  @Override
  public void update(CashfreePaymentRecord record) {
    jdbc.update(
        """
        UPDATE cashfree_payment_records SET
          cashfree_payment_id = ?, payment_method = ?, status = ?, captured_at = ?
        WHERE id = ?
        """,
        record.gatewayPaymentId(),
        record.paymentMethod(),
        record.status(),
        record.capturedAt() == null ? null : Timestamp.from(record.capturedAt()),
        record.id());
  }

  @Override
  public Optional<CashfreePaymentRecord> findById(UUID id) {
    return one("SELECT * FROM cashfree_payment_records WHERE id = ?", id);
  }

  @Override
  public Optional<CashfreePaymentRecord> findByGatewayOrderId(String gatewayOrderId) {
    return one(
        "SELECT * FROM cashfree_payment_records WHERE cashfree_order_id = ?", gatewayOrderId);
  }

  @Override
  public Optional<CashfreePaymentRecord> findByGatewayPaymentId(String gatewayPaymentId) {
    return one(
        "SELECT * FROM cashfree_payment_records WHERE cashfree_payment_id = ?", gatewayPaymentId);
  }

  private Optional<CashfreePaymentRecord> one(String sql, Object arg) {
    List<CashfreePaymentRecord> rows = jdbc.query(sql, MAPPER, arg);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
