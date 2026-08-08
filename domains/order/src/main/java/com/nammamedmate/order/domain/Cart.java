package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Cart {

  private final UUID id;
  private final UUID customerId;
  private UUID pharmacyId;
  private List<CartItem> items;
  private String couponCode;
  private long couponDiscountPaise;
  private UUID prescriptionId;
  private UUID deliveryAddressId;
  private CartStatus status;
  private final Instant createdAt;
  private Instant updatedAt;

  public Cart(
      UUID id,
      UUID customerId,
      UUID pharmacyId,
      List<CartItem> items,
      String couponCode,
      long couponDiscountPaise,
      UUID prescriptionId,
      UUID deliveryAddressId,
      CartStatus status,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.customerId = customerId;
    this.pharmacyId = pharmacyId;
    this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    this.couponCode = couponCode;
    this.couponDiscountPaise = couponDiscountPaise;
    this.prescriptionId = prescriptionId;
    this.deliveryAddressId = deliveryAddressId;
    this.status = status == null ? CartStatus.ACTIVE : status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public static Cart empty(UUID customerId, Instant now) {
    return new Cart(
        UUID.randomUUID(),
        customerId,
        null,
        List.of(),
        null,
        0L,
        null,
        null,
        CartStatus.ACTIVE,
        now,
        now);
  }

  public UUID id() {
    return id;
  }

  public UUID customerId() {
    return customerId;
  }

  public UUID pharmacyId() {
    return pharmacyId;
  }

  public List<CartItem> items() {
    return List.copyOf(items);
  }

  public String couponCode() {
    return couponCode;
  }

  public long couponDiscountPaise() {
    return couponDiscountPaise;
  }

  public UUID prescriptionId() {
    return prescriptionId;
  }

  public UUID deliveryAddressId() {
    return deliveryAddressId;
  }

  public CartStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public boolean hasRxItem() {
    return items.stream().anyMatch(CartItem::rxRequired);
  }

  public long itemTotalPaise() {
    long total = 0L;
    for (CartItem item : items) {
      total += item.lineTotalPaise();
    }
    return total;
  }

  public Optional<CartItem> findItem(UUID itemId) {
    return items.stream().filter(i -> i.itemId().equals(itemId)).findFirst();
  }

  public Optional<CartItem> findByProduct(UUID productId) {
    return items.stream().filter(i -> i.productId().equals(productId)).findFirst();
  }

  public void setPharmacyId(UUID pharmacyId) {
    this.pharmacyId = pharmacyId;
  }

  public void setCoupon(String code, long discountPaise) {
    this.couponCode = code;
    this.couponDiscountPaise = discountPaise;
  }

  public void clearCoupon() {
    this.couponCode = null;
    this.couponDiscountPaise = 0L;
  }

  public void setPrescriptionId(UUID prescriptionId) {
    this.prescriptionId = prescriptionId;
  }

  public void setDeliveryAddressId(UUID deliveryAddressId) {
    this.deliveryAddressId = deliveryAddressId;
  }

  public void setStatus(CartStatus status) {
    this.status = status;
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public void replaceItems(List<CartItem> next) {
    this.items = next == null ? new ArrayList<>() : new ArrayList<>(next);
  }

  public void addOrMerge(CartItem item) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).productId().equals(item.productId())) {
        CartItem existing = items.get(i);
        items.set(i, existing.withQuantity(existing.quantity() + item.quantity()));
        return;
      }
    }
    items.add(item);
  }

  public void updateQuantity(UUID itemId, int quantity) {
    if (quantity <= 0) {
      items.removeIf(i -> i.itemId().equals(itemId));
      return;
    }
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).itemId().equals(itemId)) {
        items.set(i, items.get(i).withQuantity(quantity));
        return;
      }
    }
  }

  public void removeItem(UUID itemId) {
    items.removeIf(i -> i.itemId().equals(itemId));
  }

  /** Clears items + pharmacy lock + coupon + prescription; keeps ACTIVE. */
  public void clearContents(Instant now) {
    this.items = new ArrayList<>();
    this.pharmacyId = null;
    clearCoupon();
    this.prescriptionId = null;
    this.status = CartStatus.ACTIVE;
    touch(now);
  }

  /** Marks cart abandoned (quote/reorder / inactivity). */
  public void abandon(Instant now) {
    this.status = CartStatus.ABANDONED;
    touch(now);
  }

  public void recomputeCouponDiscount() {
    this.couponDiscountPaise = CartPricing.couponDiscountPaise(couponCode, itemTotalPaise());
  }
}
