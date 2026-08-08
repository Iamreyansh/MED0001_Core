package com.nammamedmate.rider.application.port.out;

import java.util.UUID;

/** RazorpayX / Route payout — stub until EPIC-012/022 live integration. */
public interface RazorpayRoutePort {

  record PayoutResult(boolean success, String razorpayPayoutId, String reference, String error) {
    public static PayoutResult ok(String id, String ref) {
      return new PayoutResult(true, id, ref, null);
    }

    public static PayoutResult fail(String error) {
      return new PayoutResult(false, null, null, error);
    }
  }

  PayoutResult disburse(UUID riderId, long netPayoutPaise, UUID payoutId);
}
