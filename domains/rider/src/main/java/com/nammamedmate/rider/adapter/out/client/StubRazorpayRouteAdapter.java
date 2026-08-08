package com.nammamedmate.rider.adapter.out.client;

import com.nammamedmate.rider.application.port.out.RazorpayRoutePort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RazorpayX / Route stub (EPIC-012 live payouts later).
 *
 * <p>Fails when {@link #failNext} is set, or when riderId least-significant nibble is {@code 0xF}
 * and amount is odd — deterministic path for AC-007 tests without external calls.
 */
public class StubRazorpayRouteAdapter implements RazorpayRoutePort {

  private final AtomicBoolean failNext = new AtomicBoolean(false);

  public void failNext(boolean fail) {
    failNext.set(fail);
  }

  @Override
  public PayoutResult disburse(UUID riderId, long netPayoutPaise, UUID payoutId) {
    if (failNext.getAndSet(false)) {
      return PayoutResult.fail("stub_razorpay_route_failure");
    }
    String id = "pout_" + payoutId.toString().replace("-", "").substring(0, 14);
    String ref = "RPX-" + payoutId.toString().substring(0, 8).toUpperCase();
    return PayoutResult.ok(id, ref);
  }
}
