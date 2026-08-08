package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcRefundStore implements RefundStore {

  private static final RowMapper<Refund> MAPPER =
      (rs, rowNum) ->
          new Refund(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("order_id"),
              rs.getLong("amount_paise"),
              RefundTo.valueOf(rs.getString("refund_to")),
              rs.getString("reason"),
              rs.getString("notes"),
              RefundStatus.valueOf(rs.getString("status")),
              (UUID) rs.getObject("issued_by"),
              RefundIssuedByType.valueOf(rs.getString("issued_by_type")),
              rs.getString("razorpay_refund_id"),
              (UUID) rs.getObject("wallet_transaction_id"),
              rs.getTimestamp("processed_at") == null
                  ? null
                  : rs.getTimestamp("processed_at").toInstant(),
              rs.getString("failed_reason"),
              rs.getString("idempotency_key"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbc;

  public JdbcRefundStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Refund refund) {
    jdbc.update(
        """
        INSERT INTO refund (
          id, order_id, amount_paise, refund_to, reason, notes, status,
          issued_by, issued_by_type, razorpay_refund_id, wallet_transaction_id,
          processed_at, failed_reason, idempotency_key, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        refund.id(),
        refund.orderId(),
        refund.amountPaise(),
        refund.refundTo().name(),
        refund.reason(),
        refund.notes(),
        refund.status().name(),
        refund.issuedBy(),
        refund.issuedByType().name(),
        refund.razorpayRefundId(),
        refund.walletTransactionId(),
        refund.processedAt() == null ? null : Timestamp.from(refund.processedAt()),
        refund.failedReason(),
        refund.idempotencyKey(),
        Timestamp.from(refund.createdAt()));
  }

  @Override
  public void update(Refund refund) {
    jdbc.update(
        """
        UPDATE refund SET
          status = ?,
          razorpay_refund_id = ?,
          wallet_transaction_id = ?,
          processed_at = ?,
          failed_reason = ?
        WHERE id = ?
        """,
        refund.status().name(),
        refund.razorpayRefundId(),
        refund.walletTransactionId(),
        refund.processedAt() == null ? null : Timestamp.from(refund.processedAt()),
        refund.failedReason(),
        refund.id());
  }

  @Override
  public Optional<Refund> findById(UUID id) {
    List<Refund> rows = jdbc.query("SELECT * FROM refund WHERE id = ?", MAPPER, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    List<Refund> rows =
        jdbc.query("SELECT * FROM refund WHERE idempotency_key = ?", MAPPER, idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Refund> findByRazorpayRefundId(String razorpayRefundId) {
    if (razorpayRefundId == null || razorpayRefundId.isBlank()) {
      return Optional.empty();
    }
    List<Refund> rows =
        jdbc.query("SELECT * FROM refund WHERE razorpay_refund_id = ?", MAPPER, razorpayRefundId);
    return rows.stream().findFirst();
  }

  @Override
  public List<Refund> listByOrderId(UUID orderId) {
    return jdbc.query(
        "SELECT * FROM refund WHERE order_id = ? ORDER BY created_at ASC", MAPPER, orderId);
  }

  @Override
  public long sumSuccessfulPaise(UUID orderId) {
    Long sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0)
            FROM refund
            WHERE order_id = ? AND status IN ('INITIATED', 'PROCESSED')
            """,
            Long.class,
            orderId);
    return sum == null ? 0L : sum;
  }
}
