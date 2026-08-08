package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.Order;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {

  Order insert(Order order);

  Order update(Order order);

  Optional<Order> findById(UUID orderId);

  Optional<Order> findByCustomerAndId(UUID customerId, UUID orderId);

  Optional<Order> findByPharmacyAndId(UUID pharmacyId, UUID orderId);

  Optional<Order> findByPlacementIdempotencyKey(String idempotencyKey);

  Optional<Order> findByRazorpayOrderId(String razorpayOrderId);

  /** Allocate next daily sequence for IST date; returns 1-based seq. */
  int nextSequence(LocalDate dateIst);

  boolean hasActiveOrders(UUID customerId);

  /** Any non-deleted order for the customer (active or terminal). */
  boolean hasPlacedAnyOrder(UUID customerId);

  boolean isAddressInActiveOrder(UUID addressId);

  Optional<String> findPharmacyPhone(UUID pharmacyId);

  List<Order> findPendingAcceptanceTimedOut(Instant deadlineBefore, int limit);

  List<Order> findReadyWithoutRiderEscalation(Instant readyBefore, int limit);

  List<Order> findOpenPastSlaDeadline(Instant now, int limit);

  /** History = DELIVERED|CANCELLED only; statusFilter null/ALL = both. */
  List<Order> listCustomerHistory(UUID customerId, String statusFilter, int offset, int limit);

  long countCustomerHistory(UUID customerId, String statusFilter);

  /** Active = non-terminal statuses, newest first. */
  List<Order> listCustomerActive(UUID customerId);
}
