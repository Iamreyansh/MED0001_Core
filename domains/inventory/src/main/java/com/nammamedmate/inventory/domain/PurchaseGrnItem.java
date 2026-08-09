package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseGrnItem(
    UUID id,
    UUID grnId,
    UUID pharmacyId,
    UUID productId,
    String batchNumber,
    LocalDate expiryDate,
    LocalDate manufacturedDate,
    int quantity,
    int freeQuantity,
    long purchasePricePaise,
    long mrpPaise,
    int gstPct,
    long taxableAmountPaise,
    long gstAmountPaise,
    long lineTotalPaise,
    boolean newProduct,
    Instant createdAt,
    Instant updatedAt) {

  public int quantityTotal() {
    return quantity + freeQuantity;
  }

  /** taxable = paid qty × PTR; GST on taxable only (free units excluded). */
  public static long taxablePaise(int quantity, long purchasePricePaise) {
    return Math.multiplyExact((long) quantity, purchasePricePaise);
  }

  public static long gstPaise(long taxablePaise, int gstPct) {
    return Math.multiplyExact(taxablePaise, (long) gstPct) / 100L;
  }

  public static long lineTotalPaise(long taxablePaise, long gstPaise) {
    return Math.addExact(taxablePaise, gstPaise);
  }
}
