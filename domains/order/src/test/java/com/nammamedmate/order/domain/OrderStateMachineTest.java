package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderStateMachineTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Test
  void pharmacyAndAdminTransitions() {
    assertThat(OrderStateMachine.isPharmacyAdvance(OrderStatus.ACCEPTED, OrderStatus.PACKING))
        .isTrue();
    assertThat(
            OrderStateMachine.isPharmacyAdvance(OrderStatus.PACKING, OrderStatus.READY_FOR_PICKUP))
        .isTrue();
    assertThat(
            OrderStateMachine.isPharmacyAdvance(OrderStatus.PACKING, OrderStatus.OUT_FOR_DELIVERY))
        .isFalse();
    assertThat(OrderStateMachine.isPharmacyAdvance(null, OrderStatus.PACKING)).isFalse();
    assertThat(OrderStateMachine.isPharmacyAdvance(OrderStatus.ACCEPTED, null)).isFalse();
    assertThat(OrderStateMachine.isPharmacyAdvanceTarget(OrderStatus.PACKING)).isTrue();
    assertThat(OrderStateMachine.isPharmacyAdvanceTarget(OrderStatus.READY_FOR_PICKUP)).isTrue();
    assertThat(OrderStateMachine.isPharmacyAdvanceTarget(null)).isFalse();
    assertThat(OrderStateMachine.isPharmacyAdvanceTarget(OrderStatus.OUT_FOR_DELIVERY)).isFalse();
    assertThat(
            OrderStateMachine.isAdminForceAllowed(
                OrderStatus.PENDING_ACCEPTANCE, OrderStatus.OUT_FOR_DELIVERY))
        .isTrue();
    assertThat(OrderStateMachine.isAdminForceAllowed(OrderStatus.DELIVERED, OrderStatus.CANCELLED))
        .isFalse();
    assertThat(OrderStateMachine.isAdminForceAllowed(OrderStatus.CANCELLED, OrderStatus.ACCEPTED))
        .isFalse();
    assertThat(OrderStateMachine.isAdminForceAllowed(OrderStatus.ACCEPTED, OrderStatus.ACCEPTED))
        .isFalse();
    assertThat(OrderStateMachine.isAdminForceAllowed(null, OrderStatus.ACCEPTED)).isFalse();
    assertThat(OrderStateMachine.isAdminForceAllowed(OrderStatus.ACCEPTED, null)).isFalse();
    assertThat(OrderStateMachine.isAccept(OrderStatus.PENDING_ACCEPTANCE, OrderStatus.ACCEPTED))
        .isTrue();
    assertThat(OrderStateMachine.isAccept(OrderStatus.ACCEPTED, OrderStatus.ACCEPTED)).isFalse();
    assertThat(OrderStateMachine.isAccept(OrderStatus.PENDING_ACCEPTANCE, OrderStatus.PACKING))
        .isFalse();
    assertThat(
            OrderStateMachine.isCancelFromPending(
                OrderStatus.PENDING_ACCEPTANCE, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStateMachine.isCancelFromPending(OrderStatus.ACCEPTED, OrderStatus.CANCELLED))
        .isFalse();
    assertThat(
            OrderStateMachine.isCancelFromPending(
                OrderStatus.PENDING_ACCEPTANCE, OrderStatus.ACCEPTED))
        .isFalse();
  }

  @Test
  void orderLifecycleHelpers() {
    Order order =
        new Order(
            UUID.randomUUID(),
            "ORD-1",
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
            100,
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
    order.confirm(T0, T0.plusSeconds(600), "pay");
    assertThat(order.slaDeadline()).isEqualTo(T0.plus(Order.DELIVERY_SLA));
    assertThat(order.isAcceptanceTimedOut(T0.plusSeconds(601))).isTrue();
    assertThat(order.isAcceptanceTimedOut(T0.plusSeconds(60))).isFalse();
    assertThat(order.slaRisk(T0.plusSeconds(27 * 60))).isTrue();
    assertThat(order.slaRisk(T0.plusSeconds(10 * 60))).isFalse();
    assertThat(order.slaRisk(T0.plus(Order.DELIVERY_SLA).plusSeconds(1))).isFalse();
    assertThat(order.slaRemainingMinutesClamped(T0.plus(Order.DELIVERY_SLA).plusSeconds(60)))
        .isEqualTo(0);
    assertThat(order.slaRemainingMinutesRaw(T0.plus(Order.DELIVERY_SLA).plusSeconds(60)))
        .isLessThan(0);
    order.accept(T0);
    order.advanceTo(OrderStatus.PACKING, T0);
    assertThat(order.status()).isEqualTo(OrderStatus.PACKING);
    order.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    assertThat(order.needsRiderEscalation(T0.plus(Order.RIDER_ASSIGN_ALERT).plusSeconds(1)))
        .isTrue();
    assertThat(order.needsRiderEscalation(T0.plusSeconds(10))).isFalse();
    order.assignRider(UUID.randomUUID(), T0);
    assertThat(order.needsRiderEscalation(T0.plusSeconds(9999))).isFalse();

    Order readyNoTs =
        new Order(
            UUID.randomUUID(),
            "ORD-R",
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
            0,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.READY_FOR_PICKUP,
            null,
            null,
            null,
            T0,
            T0.plusSeconds(600),
            T0,
            T0,
            T0,
            null,
            T0.plus(Order.DELIVERY_SLA),
            false,
            null,
            null,
            null,
            null,
            null);
    assertThat(readyNoTs.needsRiderEscalation(T0.plusSeconds(9999))).isFalse();
    readyNoTs.markRiderEscalation(T0);
    assertThat(readyNoTs.needsRiderEscalation(T0.plusSeconds(9999))).isFalse();

    order.setDeliveryOtpHash("x", T0);
    order.clearDeliveryOtp(T0);
    assertThat(order.deliveryOtpHash()).isNull();
    order.advanceTo(OrderStatus.OUT_FOR_DELIVERY, T0);
    order.advanceTo(OrderStatus.DELIVERED, T0.plusSeconds(60));
    assertThat(order.slaBreached()).isFalse();
    Order late =
        new Order(
            UUID.randomUUID(),
            "ORD-L",
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
            0,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
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
    late.confirm(T0, T0.plusSeconds(600), null);
    late.advanceTo(OrderStatus.DELIVERED, T0.plus(Order.DELIVERY_SLA).plusSeconds(1));
    assertThat(late.slaBreached()).isTrue();
    late.markSlaBreached(T0);
    assertThat(late.etaMinutes(T0)).isNull();
    assertThat(late.slaRisk(T0)).isFalse();

    Order bare =
        new Order(
            UUID.randomUUID(),
            "ORD-2",
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
            0,
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
            null,
            null,
            T0,
            T0);
    assertThat(bare.slaRemainingMinutesRaw(T0)).isEqualTo(0);
    assertThat(bare.isAcceptanceTimedOut(T0)).isFalse();
    assertThat(bare.needsRiderEscalation(T0)).isFalse();
    assertThat(bare.etaMinutes(T0)).isNull();
    bare.confirm(T0, null, null);
    assertThat(bare.etaMinutes(T0)).isNull();
    assertThat(bare.slaRemainingMinutesClamped(T0.plusSeconds(60))).isEqualTo(29);
    bare.accept(T0);
    assertThat(bare.isAcceptanceTimedOut(T0.plusSeconds(9999))).isFalse();

    Order noSla =
        new Order(
            UUID.randomUUID(),
            "ORD-NS",
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
            0,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.OUT_FOR_DELIVERY,
            null,
            null,
            null,
            null,
            null,
            T0,
            T0);
    noSla.advanceTo(OrderStatus.DELIVERED, T0);
    assertThat(noSla.slaBreached()).isFalse();
    assertThat(noSla.deliveredAt()).isEqualTo(T0);
  }
}
