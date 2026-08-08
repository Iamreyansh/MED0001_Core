package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartDomainTest {

  @Test
  void mergeUpdateClearAbandonAndRx() {
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    UUID cust = UUID.randomUUID();
    Cart cart = Cart.empty(cust, now);
    UUID product = UUID.randomUUID();
    CartItem a = new CartItem(UUID.randomUUID(), product, 1, 1000, true, "A", "B", "10 tab", null);
    cart.addOrMerge(a);
    cart.addOrMerge(
        new CartItem(UUID.randomUUID(), product, 2, 1000, true, "A", "B", "10 tab", null));
    assertThat(cart.items()).hasSize(1);
    assertThat(cart.items().getFirst().quantity()).isEqualTo(3);
    assertThat(cart.hasRxItem()).isTrue();
    assertThat(cart.itemTotalPaise()).isEqualTo(3000L);

    UUID itemId = cart.items().getFirst().itemId();
    cart.updateQuantity(itemId, 1);
    assertThat(cart.items().getFirst().quantity()).isEqualTo(1);
    cart.updateQuantity(itemId, 0);
    assertThat(cart.isEmpty()).isTrue();

    cart.setPharmacyId(UUID.randomUUID());
    cart.setCoupon("NAMMA25", 100);
    cart.setPrescriptionId(UUID.randomUUID());
    cart.clearContents(now);
    assertThat(cart.pharmacyId()).isNull();
    assertThat(cart.couponCode()).isNull();
    assertThat(cart.prescriptionId()).isNull();
    assertThat(cart.status()).isEqualTo(CartStatus.ACTIVE);

    cart.abandon(now);
    assertThat(cart.status()).isEqualTo(CartStatus.ABANDONED);

    assertThatThrownBy(
            () -> new CartItem(UUID.randomUUID(), product, -1, 1, false, "x", "y", "z", null))
        .isInstanceOf(IllegalArgumentException.class);

    Cart loaded =
        new Cart(UUID.randomUUID(), cust, null, List.of(a), null, 0, null, null, null, now, now);
    assertThat(loaded.status()).isEqualTo(CartStatus.ACTIVE);
    loaded.removeItem(a.itemId());
    assertThat(loaded.isEmpty()).isTrue();
    loaded.replaceItems(null);
    assertThat(loaded.items()).isEmpty();

    Cart withNullItems =
        new Cart(
            UUID.randomUUID(), cust, null, null, null, 0, null, null, CartStatus.ACTIVE, now, now);
    assertThat(withNullItems.items()).isEmpty();
    withNullItems.setStatus(CartStatus.CHECKED_OUT);
    assertThat(withNullItems.status()).isEqualTo(CartStatus.CHECKED_OUT);
    withNullItems.updateQuantity(UUID.randomUUID(), 5); // no-op missing item
    withNullItems.findByProduct(UUID.randomUUID()).isEmpty();
    withNullItems.recomputeCouponDiscount();
    withNullItems.replaceItems(List.of(a));
    assertThat(withNullItems.items()).hasSize(1);
    withNullItems.updateQuantity(a.itemId(), 4);
    assertThat(withNullItems.items().getFirst().quantity()).isEqualTo(4);

    // updateQuantity must scan past a non-matching first item
    CartItem b =
        new CartItem(UUID.randomUUID(), UUID.randomUUID(), 1, 500, false, "B", "C", "1", null);
    withNullItems.replaceItems(List.of(a, b));
    withNullItems.updateQuantity(b.itemId(), 9);
    assertThat(withNullItems.findItem(b.itemId()).orElseThrow().quantity()).isEqualTo(9);
  }
}
