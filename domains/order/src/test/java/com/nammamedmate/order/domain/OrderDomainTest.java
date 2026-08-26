package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderDomainTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Test
  void confirmAndCodCollectAndItemSnapshot() {
    CartItem cartItem =
        new CartItem(
            UUID.randomUUID(), UUID.randomUUID(), 2, 1000, false, "Para", "Crocin", "10", null);
    OrderItemSnapshot snap = OrderItemSnapshot.fromCartItem(cartItem);
    assertThat(snap.name()).contains("Crocin");
    assertThat(
            OrderItemSnapshot.fromCartItem(
                    new CartItem(
                        UUID.randomUUID(), UUID.randomUUID(), 1, 100, false, "X", null, null, null))
                .name())
        .isEqualTo("X");
    assertThatThrownBy(() -> new OrderItemSnapshot(UUID.randomUUID(), "n", -1, 1, 1, false))
        .isInstanceOf(IllegalArgumentException.class);

    Order cod =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00001",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(snap),
            2000,
            null,
            0,
            2500,
            500,
            0,
            5000,
            PaymentMethod.COD,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            null,
            UUID.randomUUID(),
            "note",
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            "idem",
            null,
            null,
            T0,
            T0);
    Instant eta = T0.plusSeconds(600);
    cod.confirm(T0, eta, null);
    assertThat(cod.status()).isEqualTo(OrderStatus.PENDING_ACCEPTANCE);
    assertThat(cod.paymentStatus()).isEqualTo(PaymentStatus.PENDING_COLLECTION);
    assertThat(cod.slaDeadline()).isEqualTo(T0.plus(Order.DELIVERY_SLA));
    cod.markCodCollected(T0.plusSeconds(10));
    assertThat(cod.paymentStatus()).isEqualTo(PaymentStatus.COLLECTED);
    cod.markRefunded(true, T0.plusSeconds(20));
    assertThat(cod.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    cod.markRefunded(false, T0.plusSeconds(30));
    assertThat(cod.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(cod.mutableItemsCopy()).hasSize(1);
    assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
    assertThat(OrderStatus.PENDING_ACCEPTANCE.isTerminal()).isFalse();
    assertThat(PaymentMethod.UPI.isOnline()).isTrue();
    assertThat(PaymentMethod.COD.isOnline()).isFalse();

    Order nullItems =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00000",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            0,
            null,
            0,
            0,
            0,
            0,
            0,
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
            null,
            null,
            T0,
            T0);
    assertThat(nullItems.items()).isEmpty();

    Order upi =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00002",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            null,
            null,
            null,
            T0,
            T0);
    upi.markPaymentPending("order_stub_x", T0);
    assertThat(upi.gatewayOrderId()).isEqualTo("order_stub_x");
    upi.confirm(T0, eta, "pay_1");
    assertThat(upi.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(upi.gatewayPaymentId()).isEqualTo("pay_1");
  }
}
