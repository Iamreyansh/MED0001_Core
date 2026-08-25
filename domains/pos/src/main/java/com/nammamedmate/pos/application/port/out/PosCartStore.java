package com.nammamedmate.pos.application.port.out;

import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosCartStore {

  PosCart insert(PosCart cart);

  Optional<PosCart> findById(UUID pharmacyId, UUID cartId);

  PosCart update(PosCart cart);

  void touchExpiry(UUID cartId, Instant expiresAt, Instant updatedAt);

  PosCartItem insertItem(PosCartItem item);

  Optional<PosCartItem> findItem(UUID cartId, UUID itemId);

  List<PosCartItem> listItems(UUID cartId);

  PosCartItem updateItem(PosCartItem item);

  void deleteItem(UUID cartId, UUID itemId);

  int deleteAllItems(UUID cartId);

  void updateTotals(
      UUID cartId,
      long subtotalPaise,
      long gstTotalPaise,
      long discountAmountPaise,
      long grandTotalPaise,
      String discountType,
      BigDecimal discountValue,
      UUID appliedOfferId,
      Instant updatedAt,
      Instant expiresAt);

  void markCompleted(UUID cartId, UUID invoiceId, Instant updatedAt);

  Optional<UUID> findInvoiceByCheckoutIdempotency(UUID pharmacyId, String idempotencyKey);

  void saveCheckoutIdempotency(
      UUID pharmacyId, String idempotencyKey, UUID cartId, UUID invoiceId, Instant createdAt);

  int abandonExpired(Instant now);

  void attachCustomer(
      UUID cartId,
      UUID customerId,
      String name,
      String phone,
      Instant updatedAt,
      Instant expiresAt);

  void setPrescribingDoctor(UUID cartId, String doctor, Instant updatedAt, Instant expiresAt);
}
