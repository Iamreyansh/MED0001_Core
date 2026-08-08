package com.nammamedmate.order.domain;

import java.util.UUID;

/** Cart line snapshot stored in carts.items JSONB (prices in paise). */
public record CartItem(
    UUID itemId,
    UUID productId,
    int quantity,
    long unitPricePaise,
    boolean rxRequired,
    String name,
    String brand,
    String packSize,
    String imageUrl) {

  public CartItem {
    if (quantity < 0) {
      throw new IllegalArgumentException("quantity must be >= 0");
    }
  }

  public long lineTotalPaise() {
    return unitPricePaise * (long) quantity;
  }

  public CartItem withQuantity(int qty) {
    return new CartItem(
        itemId, productId, qty, unitPricePaise, rxRequired, name, brand, packSize, imageUrl);
  }
}
