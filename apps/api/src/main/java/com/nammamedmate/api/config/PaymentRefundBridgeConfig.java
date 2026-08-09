package com.nammamedmate.api.config;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.port.out.RefundFinancePort;
import com.nammamedmate.payment.application.port.out.RefundNotificationPort;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: payment refund finance façade ↔ V032/V061 {@code refund} + orders +
 * customers + payment.
 */
@Configuration
public class PaymentRefundBridgeConfig {

  @Bean
  @Primary
  RefundFinancePort jdbcRefundFinancePort(JdbcTemplate jdbc) {
    return new JdbcRefundFinanceBridge(jdbc);
  }

  @Bean
  @Primary
  RefundNotificationPort refundNotificationBridge(OutboxPublisher outbox) {
    return (customerId, refundId, orderId, amountPaise) -> {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("customer_id", customerId.toString());
      payload.put("refund_id", refundId.toString());
      payload.put("order_id", orderId.toString());
      payload.put("amount_paise", amountPaise);
      payload.put("channel", "PUSH");
      payload.put("template", "refund_completed");
      outbox.publish(
          DomainEvent.of(
              "customer.notification.refund_completed", "customer", customerId, payload));
    };
  }

  static final class JdbcRefundFinanceBridge implements RefundFinancePort {

    private static final String SELECT =
        """
        SELECT r.*,
               o.order_number,
               o.customer_id AS order_customer_id,
               o.total_payable_paise,
               o.wallet_applied_paise,
               o.payment_method AS order_payment_method,
               o.razorpay_payment_id AS order_razorpay_payment_id,
               c.name AS customer_name,
               c.phone AS customer_phone,
               CAST(NULL AS VARCHAR) AS customer_email,
               p.razorpay_payment_id AS payment_razorpay_payment_id
        FROM refund r
        LEFT JOIN orders o ON o.id = r.order_id
        LEFT JOIN customers c ON c.id = o.customer_id
        LEFT JOIN payment p ON p.order_id = r.order_id
        """;

    private final JdbcTemplate jdbc;

    JdbcRefundFinanceBridge(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Optional<RefundRecord> findById(UUID refundId) {
      List<RefundRecord> rows = jdbc.query(SELECT + " WHERE r.id = ?", this::mapRow, refundId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<RefundRecord> findByRazorpayRefundId(String razorpayRefundId) {
      if (razorpayRefundId == null || razorpayRefundId.isBlank()) {
        return Optional.empty();
      }
      List<RefundRecord> rows =
          jdbc.query(SELECT + " WHERE r.razorpay_refund_id = ?", this::mapRow, razorpayRefundId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public ListResult list(ListFilter filter) {
      StringBuilder where = new StringBuilder(" WHERE 1=1 ");
      List<Object> args = new ArrayList<>();
      appendFilters(where, args, filter);

      Long total =
          jdbc.queryForObject("SELECT COUNT(*) FROM refund r" + where, Long.class, args.toArray());
      long count = total == null ? 0L : total;

      List<Object> pageArgs = new ArrayList<>(args);
      pageArgs.add(filter.limit());
      pageArgs.add(filter.offset());
      List<RefundRecord> rows =
          jdbc.query(
              SELECT + where + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?",
              this::mapRow,
              pageArgs.toArray());
      return new ListResult(rows, count);
    }

    @Override
    public ListResult listForCustomer(UUID customerId, int limit, int offset) {
      Long total =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM refund r
              INNER JOIN orders o ON o.id = r.order_id
              WHERE o.customer_id = ?
              """,
              Long.class,
              customerId);
      List<RefundRecord> rows =
          jdbc.query(
              SELECT
                  + """
               WHERE o.customer_id = ?
               ORDER BY r.created_at DESC
               LIMIT ? OFFSET ?
              """,
              this::mapRow,
              customerId,
              limit,
              offset);
      return new ListResult(rows, total == null ? 0L : total);
    }

    @Override
    public KpiSnapshot kpis(Instant dayStart, Instant dayEnd, Instant overdueBefore) {
      Long pendingCount =
          jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE status = 'PENDING'", Long.class);
      Long pendingValue =
          jdbc.queryForObject(
              "SELECT COALESCE(SUM(amount_paise), 0) FROM refund WHERE status = 'PENDING'",
              Long.class);
      Long processedToday =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM refund
              WHERE status = 'PROCESSED'
                AND COALESCE(completed_at, processed_at) >= ? AND COALESCE(completed_at, processed_at) < ?
              """,
              Long.class,
              Timestamp.from(dayStart),
              Timestamp.from(dayEnd));
      Long failedToday =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM refund
              WHERE status = 'FAILED'
                AND processed_at >= ? AND processed_at < ?
              """,
              Long.class,
              Timestamp.from(dayStart),
              Timestamp.from(dayEnd));
      Long overdue =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM refund
              WHERE status = 'PENDING' AND created_at < ?
              """,
              Long.class,
              Timestamp.from(overdueBefore));
      return new KpiSnapshot(
          pendingCount == null ? 0 : pendingCount,
          pendingValue == null ? 0 : pendingValue,
          processedToday == null ? 0 : processedToday,
          failedToday == null ? 0 : failedToday,
          overdue == null ? 0 : overdue);
    }

    @Override
    public boolean claimForProcess(UUID refundId, UUID processedBy, String notes, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE refund SET
                status = 'INITIATED',
                processed_by = ?,
                notes = COALESCE(?, notes),
                processed_at = ?
              WHERE id = ? AND status = 'PENDING'
              """,
              processedBy,
              blankToNull(notes),
              Timestamp.from(now),
              refundId);
      return updated == 1;
    }

    @Override
    public boolean finalizeGatewayProcess(
        UUID refundId, String razorpayRefundId, LocalDate expectedBy, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE refund SET
                razorpay_refund_id = ?,
                expected_by = ?,
                processed_at = COALESCE(processed_at, ?)
              WHERE id = ? AND status = 'INITIATED'
              """,
              razorpayRefundId,
              expectedBy == null ? null : Date.valueOf(expectedBy),
              Timestamp.from(now),
              refundId);
      return updated == 1;
    }

    @Override
    public void attachGatewayRefundId(UUID refundId, String razorpayRefundId, Instant now) {
      jdbc.update(
          """
          UPDATE refund SET
            razorpay_refund_id = COALESCE(razorpay_refund_id, ?),
            processed_at = COALESCE(processed_at, ?)
          WHERE id = ? AND status = 'INITIATED'
          """,
          razorpayRefundId,
          Timestamp.from(now),
          refundId);
    }

    @Override
    public void markProcessFailed(UUID refundId, String reason, Instant now) {
      jdbc.update(
          """
          UPDATE refund SET
            status = 'FAILED',
            failed_reason = ?,
            processed_at = ?
          WHERE id = ? AND status IN ('PENDING', 'INITIATED')
          """,
          reason == null ? "failed" : truncate(reason, 300),
          Timestamp.from(now),
          refundId);
    }

    @Override
    public boolean markCompleted(UUID refundId, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE refund SET
                status = 'PROCESSED',
                processed_at = COALESCE(processed_at, ?),
                completed_at = ?
              WHERE id = ? AND status IN ('INITIATED', 'PENDING')
              """,
              Timestamp.from(now),
              Timestamp.from(now),
              refundId);
      return updated == 1;
    }

    @Override
    public boolean markWalletCompleted(
        UUID refundId, UUID walletTxId, UUID processedBy, String notes, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE refund SET
                status = 'PROCESSED',
                wallet_transaction_id = ?,
                processed_by = COALESCE(?, processed_by),
                notes = COALESCE(?, notes),
                processed_at = ?,
                completed_at = ?
              WHERE id = ? AND status = 'INITIATED'
              """,
              walletTxId,
              processedBy,
              blankToNull(notes),
              Timestamp.from(now),
              Timestamp.from(now),
              refundId);
      return updated == 1;
    }

    private static void appendFilters(StringBuilder where, List<Object> args, ListFilter filter) {
      if (filter.storageStatus() != null && !filter.storageStatus().isBlank()) {
        where.append(" AND r.status = ? ");
        args.add(filter.storageStatus());
      }
      if (filter.storageRefundTo() != null && !filter.storageRefundTo().isBlank()) {
        where.append(" AND r.refund_to = ? ");
        args.add(filter.storageRefundTo());
      }
      if (filter.createdFrom() != null) {
        where.append(" AND r.created_at >= ? ");
        args.add(Timestamp.from(filter.createdFrom()));
      }
    }

    private RefundRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      UUID orderCustomerId = (UUID) rs.getObject("order_customer_id");
      String orderPayId = columnString(rs, "order_razorpay_payment_id");
      String paymentPayId = columnString(rs, "payment_razorpay_payment_id");
      String razorpayPaymentId =
          orderPayId != null && !orderPayId.isBlank() ? orderPayId : paymentPayId;
      return new RefundRecord(
          (UUID) rs.getObject("id"),
          (UUID) rs.getObject("order_id"),
          columnString(rs, "order_number"),
          orderCustomerId,
          columnString(rs, "customer_name"),
          columnString(rs, "customer_phone"),
          columnString(rs, "customer_email"),
          rs.getLong("amount_paise"),
          columnLong(rs, "total_payable_paise"),
          columnLong(rs, "wallet_applied_paise"),
          rs.getString("refund_to"),
          rs.getString("status"),
          rs.getString("reason"),
          columnString(rs, "notes"),
          columnString(rs, "order_payment_method"),
          columnString(rs, "razorpay_refund_id"),
          razorpayPaymentId,
          (UUID) rs.getObject("wallet_transaction_id"),
          columnBool(rs, "auto_processed"),
          (UUID) rs.getObject("issued_by"),
          (UUID) rs.getObject("processed_by"),
          tsInstant(rs, "processed_at"),
          tsInstant(rs, "completed_at"),
          dateValue(rs, "expected_by"),
          columnString(rs, "failed_reason"),
          tsInstant(rs, "created_at"));
    }

    private static long columnLong(ResultSet rs, String col) throws SQLException {
      try {
        long v = rs.getLong(col);
        return rs.wasNull() ? 0L : v;
      } catch (SQLException e) {
        return 0L;
      }
    }

    private static boolean columnBool(ResultSet rs, String col) throws SQLException {
      try {
        return rs.getBoolean(col);
      } catch (SQLException e) {
        return false;
      }
    }

    private static String columnString(ResultSet rs, String col) throws SQLException {
      try {
        return rs.getString(col);
      } catch (SQLException e) {
        return null;
      }
    }

    private static Instant tsInstant(ResultSet rs, String col) throws SQLException {
      try {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
      } catch (SQLException e) {
        return null;
      }
    }

    private static LocalDate dateValue(ResultSet rs, String col) throws SQLException {
      try {
        return rs.getObject(col, LocalDate.class);
      } catch (SQLException e) {
        return null;
      }
    }

    private static String blankToNull(String s) {
      if (s == null || s.isBlank()) {
        return null;
      }
      return truncate(s.trim(), 500);
    }

    private static String truncate(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max);
    }
  }
}
