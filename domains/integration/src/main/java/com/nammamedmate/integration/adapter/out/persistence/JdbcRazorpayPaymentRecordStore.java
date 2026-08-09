package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.RazorpayPaymentRecordStore;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcRazorpayPaymentRecordStore implements RazorpayPaymentRecordStore {

  private final JdbcTemplate jdbc;

  public JdbcRazorpayPaymentRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<RazorpayPaymentRecord> MAPPER =
      (rs, i) ->
          new RazorpayPaymentRecord(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("platform_order_id"),
              rs.getString("razorpay_order_id"),
              rs.getString("razorpay_payment_id"),
              rs.getInt("amount_paise"),
              rs.getString("currency"),
              rs.getString("payment_method"),
              rs.getString("status"),
              instant(rs.getTimestamp("created_at")),
              instant(rs.getTimestamp("captured_at")));

  @Override
  public void insert(RazorpayPaymentRecord record) {
    jdbc.update(
        """
        INSERT INTO razorpay_payment_records (
          id, platform_order_id, razorpay_order_id, razorpay_payment_id,
          amount_paise, currency, payment_method, status, created_at, captured_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.platformOrderId(),
        record.razorpayOrderId(),
        record.razorpayPaymentId(),
        record.amountPaise(),
        record.currency(),
        record.paymentMethod(),
        record.status(),
        Timestamp.from(record.createdAt()),
        record.capturedAt() == null ? null : Timestamp.from(record.capturedAt()));
  }

  @Override
  public void update(RazorpayPaymentRecord record) {
    jdbc.update(
        """
        UPDATE razorpay_payment_records SET
          razorpay_payment_id = ?, payment_method = ?, status = ?, captured_at = ?
        WHERE id = ?
        """,
        record.razorpayPaymentId(),
        record.paymentMethod(),
        record.status(),
        record.capturedAt() == null ? null : Timestamp.from(record.capturedAt()),
        record.id());
  }

  @Override
  public Optional<RazorpayPaymentRecord> findById(UUID id) {
    return one("SELECT * FROM razorpay_payment_records WHERE id = ?", id);
  }

  @Override
  public Optional<RazorpayPaymentRecord> findByRazorpayOrderId(String razorpayOrderId) {
    return one(
        "SELECT * FROM razorpay_payment_records WHERE razorpay_order_id = ?", razorpayOrderId);
  }

  @Override
  public Optional<RazorpayPaymentRecord> findByRazorpayPaymentId(String razorpayPaymentId) {
    return one(
        "SELECT * FROM razorpay_payment_records WHERE razorpay_payment_id = ?", razorpayPaymentId);
  }

  private Optional<RazorpayPaymentRecord> one(String sql, Object arg) {
    List<RazorpayPaymentRecord> rows = jdbc.query(sql, MAPPER, arg);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
