package com.nammamedmate.order.domain;

import java.util.UUID;

/** Quote line: pricePaise is the line total (not unit). */
public record QuotedMedicine(String name, int quantity, long pricePaise, UUID productId) {

  public QuotedMedicine(String name, int quantity, long pricePaise) {
    this(name, quantity, pricePaise, null);
  }

  public QuotedMedicine {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    if (pricePaise < 0) {
      throw new IllegalArgumentException("price must be >= 0");
    }
  }
}
