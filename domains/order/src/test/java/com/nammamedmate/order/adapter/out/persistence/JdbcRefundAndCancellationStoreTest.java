package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.OrderCancellation;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcRefundAndCancellationStoreTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void refundStoreCrud() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRefundStore store = new JdbcRefundStore(jdbc);
    UUID id = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Refund refund =
        new Refund(
            id,
            orderId,
            5000,
            RefundTo.SOURCE,
            "reason",
            "notes",
            RefundStatus.INITIATED,
            UUID.randomUUID(),
            RefundIssuedByType.ADMIN,
            "rfnd_1",
            null,
            null,
            null,
            "idem",
            T0);
    store.insert(refund);
    verify(jdbc).update(anyString(), any(Object[].class));
    Refund processed =
        new Refund(
            id,
            orderId,
            5000,
            RefundTo.SOURCE,
            "reason",
            "notes",
            RefundStatus.PROCESSED,
            refund.issuedBy(),
            RefundIssuedByType.ADMIN,
            "rfnd_1",
            null,
            T0,
            null,
            "idem",
            T0);
    store.insert(processed);
    store.update(refund); // null processedAt branch
    refund.markProcessed(T0);
    store.update(refund);
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<Refund> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getLong("amount_paise")).thenReturn(5000L);
              when(rs.getString("refund_to")).thenReturn("SOURCE");
              when(rs.getString("reason")).thenReturn("reason");
              when(rs.getString("notes")).thenReturn("notes");
              when(rs.getString("status")).thenReturn("PROCESSED");
              when(rs.getObject("issued_by")).thenReturn(refund.issuedBy());
              when(rs.getString("issued_by_type")).thenReturn("ADMIN");
              when(rs.getString("razorpay_refund_id")).thenReturn("rfnd_1");
              when(rs.getObject("wallet_transaction_id")).thenReturn(null);
              when(rs.getTimestamp("processed_at")).thenReturn(Timestamp.from(T0));
              when(rs.getString("failed_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn("idem");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(T0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    UUID nullProcessedId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(nullProcessedId)))
        .thenAnswer(
            inv -> {
              RowMapper<Refund> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(nullProcessedId);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getString("refund_to")).thenReturn("WALLET");
              when(rs.getString("reason")).thenReturn("r");
              when(rs.getString("notes")).thenReturn(null);
              when(rs.getString("status")).thenReturn("PROCESSED");
              when(rs.getObject("issued_by")).thenReturn(null);
              when(rs.getString("issued_by_type")).thenReturn("SYSTEM");
              when(rs.getString("razorpay_refund_id")).thenReturn(null);
              when(rs.getObject("wallet_transaction_id")).thenReturn(UUID.randomUUID());
              when(rs.getTimestamp("processed_at")).thenReturn(null);
              when(rs.getString("failed_reason")).thenReturn(null);
              when(rs.getString("idempotency_key")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(T0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(nullProcessedId)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("idem"))).thenReturn(List.of(refund));
    assertThat(store.findByIdempotencyKey("idem")).isPresent();
    assertThat(store.findByIdempotencyKey(" ")).isEmpty();
    assertThat(store.findByIdempotencyKey(null)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("rfnd_1"))).thenReturn(List.of(refund));
    assertThat(store.findByRazorpayRefundId("rfnd_1")).isPresent();
    assertThat(store.findByRazorpayRefundId(null)).isEmpty();
    assertThat(store.findByRazorpayRefundId(" ")).isEmpty();

    refund.markFailed("x", T0);
    store.update(refund);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId))).thenReturn(List.of(refund));
    assertThat(store.listByOrderId(orderId)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(orderId))).thenReturn(5000L);
    assertThat(store.sumSuccessfulPaise(orderId)).isEqualTo(5000L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(orderId))).thenReturn(null);
    assertThat(store.sumSuccessfulPaise(orderId)).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void cancellationStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcOrderCancellationStore store = new JdbcOrderCancellationStore(jdbc);
    UUID id = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderCancellation row =
        new OrderCancellation(id, orderId, CancelledByType.SYSTEM, null, "TIMEOUT", T0);
    store.insert(row);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId)))
        .thenAnswer(
            inv -> {
              RowMapper<OrderCancellation> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getString("cancelled_by_type")).thenReturn("SYSTEM");
              when(rs.getObject("cancelled_by_id")).thenReturn(null);
              when(rs.getString("reason")).thenReturn("TIMEOUT");
              when(rs.getTimestamp("cancelled_at")).thenReturn(Timestamp.from(T0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByOrderId(orderId)).isPresent();
  }
}
