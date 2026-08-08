package com.nammamedmate.order.domain;

import java.util.UUID;

/** Immutable line snapshot stored in orders.items JSONB (prices in paise). */
public record OrderItemSnapshot(
    UUID productId,
    String name,
    int quantity,
    long unitPricePaise,
    long lineTotalPaise,
    boolean rxRequired) {

  public OrderItemSnapshot {
    if (quantity < 0) {
      throw new IllegalArgumentException("quantity must be >= 0");
    }
  }

  public static OrderItemSnapshot fromCartItem(CartItem item) {
    return new OrderItemSnapshot(
        item.productId(),
        displayName(item),
        item.quantity(),
        item.unitPricePaise(),
        item.lineTotalPaise(),
        item.rxRequired());
  }

  private static String displayName(CartItem item) {
    if (item.brand() == null || item.brand().isBlank()) {
      return item.name();
    }
    return item.name() + " (" + item.brand() + ")";
  }
}
