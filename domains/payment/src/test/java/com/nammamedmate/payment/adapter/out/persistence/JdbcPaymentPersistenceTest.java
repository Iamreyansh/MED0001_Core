package com.nammamedmate.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.payment.domain.Payment;
import com.nammamedmate.payment.domain.PaymentMethod;
import com.nammamedmate.payment.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcPaymentPersistenceTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ResultSet rs;

  private final ObjectMapper om = new ObjectMapper();
  private final Instant now = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void paymentStoreInsertUpdateFind() throws Exception {
    JdbcPaymentStore store = new JdbcPaymentStore(jdbc, om);
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1000,
            100,
            900,
            "INR",
            PaymentMethod.UPI,
            PaymentStatus.PENDING,
            "order_1",
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            now,
            now);
    store.insert(payment);
    payment.capture("pay_1", "sig", 20L, "{\"ok\":true}", now);
    payment.appendWebhookEvent("payment.captured");
    store.update(payment);
    verify(jdbc, atLeastOnce())
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(payment.id());
              when(rs.getObject("order_id")).thenReturn(payment.orderId());
              when(rs.getObject("customer_id")).thenReturn(payment.customerId());
              when(rs.getLong("amount_paise")).thenReturn(1000L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(100L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(900L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("UPI");
              when(rs.getString("status")).thenReturn("CAPTURED");
              when(rs.getString("razorpay_order_id")).thenReturn("order_1");
              when(rs.getString("razorpay_payment_id")).thenReturn("pay_1");
              when(rs.getString("razorpay_signature")).thenReturn("sig");
              when(rs.getObject("gateway_fee_paise")).thenReturn(20L);
              when(rs.getObject("gateway_response")).thenReturn("{\"ok\":true}");
              when(rs.getObject("webhook_events")).thenReturn("[\"payment.captured\"]");
              when(rs.getTimestamp("captured_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findById(payment.id())).isPresent();
    assertThat(store.findByOrderId(payment.orderId())).isPresent();
    assertThat(store.findByRazorpayOrderId("order_1")).isPresent();
    assertThat(store.findByRazorpayPaymentId("pay_1")).isPresent();
  }

  @Test
  @SuppressWarnings("unchecked")
  void paymentStoreHandlesBadJsonEvents() throws Exception {
    JdbcPaymentStore store = new JdbcPaymentStore(jdbc, om);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              UUID id = UUID.randomUUID();
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("customer_id")).thenReturn(UUID.randomUUID());
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(0L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(1L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("CARD");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getString("razorpay_signature")).thenReturn(null);
              when(rs.getObject("gateway_fee_paise")).thenReturn(null);
              when(rs.getObject("gateway_response")).thenReturn(null);
              when(rs.getObject("webhook_events")).thenReturn("not-json");
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    Optional<Payment> row = store.findById(UUID.randomUUID());
    assertThat(row).isPresent();
    assertThat(row.get().webhookEvents()).isEmpty();
  }

  @Test
  void ledgerWriterAppendsAndGuards() {
    JdbcFinancialLedgerWriter writer = new JdbcFinancialLedgerWriter(jdbc, om);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    writer.append("ORDER_GMV", UUID.randomUUID(), "PAYMENT", 100, 0, "gmv", Map.of("k", "v"));
    writer.append("SKIP", UUID.randomUUID(), "PAYMENT", 0, 0, "skip", null);
    writer.append("NULL_META", UUID.randomUUID(), "PAYMENT", 50, 0, "null meta", null);
    writer.append("Y", UUID.randomUUID(), "PAYMENT", 0, 10, "debit", Map.of());
    assertThatThrownBy(() -> writer.append("X", UUID.randomUUID(), "PAYMENT", 1, 1, "bad", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ledgerWriterEncodeFailureAndDebitOnly() throws Exception {
    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    JdbcFinancialLedgerWriter writer = new JdbcFinancialLedgerWriter(jdbc, boom);
    assertThatThrownBy(
            () ->
                writer.append(
                    "ORDER_GMV", UUID.randomUUID(), "PAYMENT", 100, 0, "gmv", Map.of("k", "v")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void paymentStoreWriteEventsFailureAndBlankEvents() throws Exception {
    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    JdbcPaymentStore store = new JdbcPaymentStore(jdbc, boom);
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            0,
            1,
            "INR",
            PaymentMethod.UPI,
            PaymentStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            List.of("e"),
            null,
            null,
            null,
            null,
            now,
            now);
    store.insert(payment);

    JdbcPaymentStore reader = new JdbcPaymentStore(jdbc, om);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(payment.id());
              when(rs.getObject("order_id")).thenReturn(payment.orderId());
              when(rs.getObject("customer_id")).thenReturn(payment.customerId());
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(0L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(1L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("UPI");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getString("razorpay_signature")).thenReturn(null);
              when(rs.getObject("gateway_fee_paise")).thenReturn(null);
              when(rs.getObject("gateway_response")).thenReturn(null);
              when(rs.getObject("webhook_events")).thenReturn("   ");
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(reader.findById(payment.id()).orElseThrow().webhookEvents()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(payment.id());
              when(rs.getObject("order_id")).thenReturn(payment.orderId());
              when(rs.getObject("customer_id")).thenReturn(payment.customerId());
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(0L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(1L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("UPI");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getString("razorpay_signature")).thenReturn(null);
              when(rs.getObject("gateway_fee_paise")).thenReturn(null);
              when(rs.getObject("gateway_response")).thenReturn(null);
              when(rs.getObject("webhook_events")).thenReturn(null);
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(reader.findById(payment.id()).orElseThrow().webhookEvents()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(payment.id());
              when(rs.getObject("order_id")).thenReturn(payment.orderId());
              when(rs.getObject("customer_id")).thenReturn(payment.customerId());
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(0L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(1L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("UPI");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getString("razorpay_signature")).thenReturn(null);
              when(rs.getObject("gateway_fee_paise")).thenReturn(null);
              when(rs.getObject("gateway_response")).thenReturn(null);
              when(rs.getObject("webhook_events")).thenReturn("");
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(reader.findById(payment.id()).orElseThrow().webhookEvents()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void findByIdempotencyKeySkipsBlankAndMapsRow() throws Exception {
    JdbcPaymentStore store = new JdbcPaymentStore(jdbc, om);
    assertThat(store.findByIdempotencyKey(null)).isEmpty();
    assertThat(store.findByIdempotencyKey("  ")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findByIdempotencyKey("missing")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Payment> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("order_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("customer_id")).thenReturn(UUID.randomUUID());
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getLong("wallet_portion_paise")).thenReturn(0L);
              when(rs.getLong("gateway_portion_paise")).thenReturn(1L);
              when(rs.getString("currency")).thenReturn("INR");
              when(rs.getString("method")).thenReturn("UPI");
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("razorpay_order_id")).thenReturn(null);
              when(rs.getString("razorpay_payment_id")).thenReturn(null);
              when(rs.getString("razorpay_signature")).thenReturn(null);
              when(rs.getObject("gateway_fee_paise")).thenReturn(null);
              when(rs.getObject("gateway_response")).thenReturn(null);
              when(rs.getObject("webhook_events")).thenReturn(null);
              when(rs.getTimestamp("captured_at")).thenReturn(null);
              when(rs.getTimestamp("failed_at")).thenReturn(null);
              when(rs.getString("failure_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn("idem-1");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByIdempotencyKey("idem-1")).isPresent();
  }
}
