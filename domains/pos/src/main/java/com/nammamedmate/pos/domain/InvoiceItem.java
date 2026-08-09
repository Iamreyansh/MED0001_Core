package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceItem(
    UUID id,
    UUID invoiceId,
    UUID productId,
    String productName,
    String hsnCode,
    UUID batchId,
    String batchNumber,
    LocalDate expiryDate,
    Integer packSize,
    int quantity,
    boolean isLoose,
    long unitPricePaise,
    int gstPct,
    long lineSubtotalPaise,
    long gstAmountPaise,
    long lineTotalPaise,
    boolean isRxOnly,
    Instant createdAt) {

  public static InvoiceItem fromCartItem(UUID id, UUID invoiceId, PosCartItem item, Instant now) {
    return new InvoiceItem(
        id,
        invoiceId,
        item.productId(),
        item.productName(),
        item.hsnCode(),
        item.batchId(),
        item.batchNumber(),
        item.expiryDate(),
        item.packSize(),
        item.quantity(),
        item.isLoose(),
        item.unitPricePaise(),
        item.gstPct(),
        item.lineSubtotalPaise(),
        item.gstAmountPaise(),
        item.lineTotalPaise(),
        item.isRxOnly(),
        now);
  }
}
