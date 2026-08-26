package com.nammamedmate.payment.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.payment.application.port.out.PaymentStore;
import com.nammamedmate.payment.domain.Payment;
import com.nammamedmate.payment.domain.PaymentMethod;
import com.nammamedmate.payment.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentStore implements PaymentStore {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RowMapper<Payment> mapper;

  public JdbcPaymentStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.mapper = (rs, i) -> mapRow(rs);
  }

  @Override
  public Payment insert(Payment payment) {
    jdbc.update(
        """
        INSERT INTO payment (
          id, order_id, customer_id, amount_paise, wallet_portion_paise, gateway_portion_paise,
          currency, method, status, gateway_order_id, gateway_payment_id, gateway_signature,
          gateway_fee_paise, gateway_response, webhook_events, captured_at, failed_at,
          failure_reason, idempotency_key, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)
        """,
        payment.id(),
        payment.orderId(),
        payment.customerId(),
        payment.amountPaise(),
        payment.walletPortionPaise(),
        payment.gatewayPortionPaise(),
        payment.currency(),
        payment.method().name(),
        payment.status().name(),
        payment.gatewayOrderId(),
        payment.gatewayPaymentId(),
        payment.gatewaySignature(),
        payment.gatewayFeePaise(),
        payment.gatewayResponseJson(),
        writeEvents(payment.webhookEvents()),
        ts(payment.capturedAt()),
        ts(payment.failedAt()),
        payment.failureReason(),
        payment.idempotencyKey(),
        Timestamp.from(payment.createdAt()),
        Timestamp.from(payment.updatedAt()));
    return payment;
  }

  @Override
  public Payment update(Payment payment) {
    jdbc.update(
        """
        UPDATE payment SET
          amount_paise = ?, wallet_portion_paise = ?, gateway_portion_paise = ?,
          currency = ?, method = ?, status = ?, gateway_order_id = ?, gateway_payment_id = ?,
          gateway_signature = ?, gateway_fee_paise = ?, gateway_response = ?::jsonb,
          webhook_events = ?::jsonb, captured_at = ?, failed_at = ?, failure_reason = ?,
          idempotency_key = ?, updated_at = ?
        WHERE id = ?
        """,
        payment.amountPaise(),
        payment.walletPortionPaise(),
        payment.gatewayPortionPaise(),
        payment.currency(),
        payment.method().name(),
        payment.status().name(),
        payment.gatewayOrderId(),
        payment.gatewayPaymentId(),
        payment.gatewaySignature(),
        payment.gatewayFeePaise(),
        payment.gatewayResponseJson(),
        writeEvents(payment.webhookEvents()),
        ts(payment.capturedAt()),
        ts(payment.failedAt()),
        payment.failureReason(),
        payment.idempotencyKey(),
        Timestamp.from(payment.updatedAt()),
        payment.id());
    return payment;
  }

  @Override
  public Optional<Payment> findById(UUID paymentId) {
    List<Payment> rows = jdbc.query("SELECT * FROM payment WHERE id = ?", mapper, paymentId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Payment> findByOrderId(UUID orderId) {
    List<Payment> rows = jdbc.query("SELECT * FROM payment WHERE order_id = ?", mapper, orderId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Payment> findByGatewayOrderId(String gatewayOrderId) {
    List<Payment> rows =
        jdbc.query("SELECT * FROM payment WHERE gateway_order_id = ?", mapper, gatewayOrderId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    List<Payment> rows =
        jdbc.query(
            "SELECT * FROM payment WHERE idempotency_key = ?", mapper, idempotencyKey.trim());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId) {
    List<Payment> rows =
        jdbc.query("SELECT * FROM payment WHERE gateway_payment_id = ?", mapper, gatewayPaymentId);
    return rows.stream().findFirst();
  }

  private Payment mapRow(ResultSet rs) throws SQLException {
    return new Payment(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("order_id"),
        (UUID) rs.getObject("customer_id"),
        rs.getLong("amount_paise"),
        rs.getLong("wallet_portion_paise"),
        rs.getLong("gateway_portion_paise"),
        rs.getString("currency"),
        PaymentMethod.valueOf(rs.getString("method")),
        PaymentStatus.valueOf(rs.getString("status")),
        rs.getString("gateway_order_id"),
        rs.getString("gateway_payment_id"),
        rs.getString("gateway_signature"),
        (Long) rs.getObject("gateway_fee_paise"),
        readJsonText(rs.getObject("gateway_response")),
        readEvents(rs.getObject("webhook_events")),
        instant(rs.getTimestamp("captured_at")),
        instant(rs.getTimestamp("failed_at")),
        rs.getString("failure_reason"),
        rs.getString("idempotency_key"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private String writeEvents(List<String> events) {
    try {
      return objectMapper.writeValueAsString(events);
    } catch (Exception e) {
      return "[]";
    }
  }

  private List<String> readEvents(Object raw) {
    if (raw == null) {
      return Collections.emptyList();
    }
    try {
      String json = raw.toString().trim();
      if (json.isEmpty()) {
        return Collections.emptyList();
      }
      return objectMapper.readValue(json, STRING_LIST);
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  private static String readJsonText(Object raw) {
    return raw == null ? null : raw.toString();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
