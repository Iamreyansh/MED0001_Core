package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LifecycleStubsAndEventStoreTest {

  @Test
  void stubsAndEventStore() throws Exception {
    assertThat(new StubRiderLookupAdapter().findById(null)).isEmpty();
    assertThat(new StubRiderLookupAdapter().findById(UUID.randomUUID())).isPresent();

    StubRefundInitiatorAdapter refunds = new StubRefundInitiatorAdapter();
    assertThat(refunds.initiate(null, "x", ActorType.SYSTEM, null).initiated()).isFalse();
    Instant t0 = Instant.parse("2026-08-08T06:00:00Z");
    Order upi =
        new Order(
            UUID.randomUUID(),
            "O",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            0,
            null,
            0,
            0,
            0,
            0,
            500,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            t0,
            t0,
            t0,
            t0);
    assertThat(refunds.initiate(upi, "r", ActorType.SYSTEM, null).refundTo()).isEqualTo("SOURCE");
    Order wallet =
        new Order(
            UUID.randomUUID(),
            "O2",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            0,
            null,
            0,
            0,
            0,
            0,
            500,
            PaymentMethod.WALLET,
            PaymentStatus.PAID,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            t0,
            t0,
            t0,
            t0);
    assertThat(refunds.initiate(wallet, "r", ActorType.CUSTOMER, null).refundTo())
        .isEqualTo("WALLET");
    Order cod =
        new Order(
            UUID.randomUUID(),
            "O3",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            0,
            null,
            0,
            0,
            0,
            0,
            500,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            t0,
            t0,
            t0,
            t0);
    assertThat(refunds.initiate(cod, "r", ActorType.ADMIN, null).initiated()).isFalse();

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcOrderStatusEventStore store = new JdbcOrderStatusEventStore(jdbc);
    OrderStatusEvent event =
        new OrderStatusEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.PENDING_ACCEPTANCE,
            OrderStatus.ACCEPTED,
            ActorType.PHARMACY,
            UUID.randomUUID(),
            "n",
            t0);
    store.append(event);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class)))
        .thenAnswer(
            inv -> {
              RowMapper<OrderStatusEvent> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(event.id());
              when(rs.getObject("order_id")).thenReturn(event.orderId());
              when(rs.getString("from_status")).thenReturn("PENDING_ACCEPTANCE");
              when(rs.getString("to_status")).thenReturn("ACCEPTED");
              when(rs.getString("actor_type")).thenReturn("PHARMACY");
              when(rs.getObject("actor_id")).thenReturn(event.actorId());
              when(rs.getString("notes")).thenReturn("n");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t0));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listByOrderId(event.orderId())).hasSize(1);

    RefundInitiatorPort.RefundPlan plan = new RefundInitiatorPort.RefundPlan(true, 1, "SOURCE");
    assertThat(plan.amountPaise()).isEqualTo(1);
  }
}
