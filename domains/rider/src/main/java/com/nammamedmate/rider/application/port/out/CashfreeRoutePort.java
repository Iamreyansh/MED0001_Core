package com.nammamedmate.rider.application.port.out;

import java.util.UUID;

/** Cashfree payout route for rider disbursements (bridged from payment domain in apps/api). */
public interface CashfreeRoutePort {

  record PayoutResult(boolean success, String cashfreeTransferId, String reference, String error) {
    public static PayoutResult ok(String id, String ref) {
      return new PayoutResult(true, id, ref, null);
    }

    public static PayoutResult fail(String error) {
      return new PayoutResult(false, null, null, error);
    }
  }

  PayoutResult disburse(UUID riderId, long netPayoutPaise, UUID payoutId);
}
