package com.nammamedmate.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** Canonical order lifecycle transitions (STORY-005). */
public final class OrderStateMachine {

  private static final Set<OrderStatus> PHARMACY_ADVANCE_TO =
      EnumSet.of(OrderStatus.PACKING, OrderStatus.READY_FOR_PICKUP);

  private OrderStateMachine() {}

  public static boolean isPharmacyAdvance(OrderStatus from, OrderStatus to) {
    if (from == null || to == null) {
      return false;
    }
    return (from == OrderStatus.ACCEPTED && to == OrderStatus.PACKING)
        || (from == OrderStatus.PACKING && to == OrderStatus.READY_FOR_PICKUP);
  }

  public static boolean isPharmacyAdvanceTarget(OrderStatus to) {
    return to != null && PHARMACY_ADVANCE_TO.contains(to);
  }

  /** Admin may force any transition from a non-terminal status. */
  public static boolean isAdminForceAllowed(OrderStatus from, OrderStatus to) {
    if (from == null || to == null) {
      return false;
    }
    if (from.isTerminal()) {
      return false;
    }
    return from != to;
  }

  public static boolean isAccept(OrderStatus from, OrderStatus to) {
    return from == OrderStatus.PENDING_ACCEPTANCE && to == OrderStatus.ACCEPTED;
  }

  public static boolean isCancelFromPending(OrderStatus from, OrderStatus to) {
    return from == OrderStatus.PENDING_ACCEPTANCE && to == OrderStatus.CANCELLED;
  }
}
