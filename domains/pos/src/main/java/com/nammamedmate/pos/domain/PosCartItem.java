package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PosCartItem(
    UUID id,
    UUID cartId,
    UUID productId,
    String productName,
    UUID batchId,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    boolean isLoose,
    long unitPricePaise,
    int gstPct,
    long lineSubtotalPaise,
    long gstAmountPaise,
    long lineTotalPaise,
    boolean isRxOnly,
    int packSize,
    String hsnCode,
    Instant createdAt) {

  public static PosCartItem compute(
      UUID id,
      UUID cartId,
      UUID productId,
      String productName,
      UUID batchId,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      boolean isLoose,
      long unitPricePaise,
      int gstPct,
      boolean isRxOnly,
      int packSize,
      String hsnCode,
      Instant createdAt) {
    long line = Math.multiplyExact((long) quantity, unitPricePaise);
    long gst = MoneyMath.gstFromInclusive(line, gstPct);
    return new PosCartItem(
        id,
        cartId,
        productId,
        productName,
        batchId,
        batchNumber,
        expiryDate,
        quantity,
        isLoose,
        unitPricePaise,
        gstPct,
        line,
        gst,
        line,
        isRxOnly,
        packSize,
        hsnCode,
        createdAt);
  }
}
