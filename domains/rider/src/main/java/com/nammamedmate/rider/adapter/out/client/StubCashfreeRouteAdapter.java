package com.nammamedmate.rider.adapter.out.client;

import com.nammamedmate.rider.application.port.out.CashfreeRoutePort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CashfreePayout / Route stub (EPIC-012 live payouts later).
 *
 * <p>Fails when {@link #failNext} is set, or when riderId least-significant nibble is {@code 0xF}
 * and amount is odd — deterministic path for AC-007 tests without external calls.
 */
public class StubCashfreeRouteAdapter implements CashfreeRoutePort {

  private final AtomicBoolean failNext = new AtomicBoolean(false);

  public void failNext(boolean fail) {
    failNext.set(fail);
  }

  @Override
  public PayoutResult disburse(UUID riderId, long netPayoutPaise, UUID payoutId) {
    if (failNext.getAndSet(false)) {
      return PayoutResult.fail("stub_cashfree_route_failure");
    }
    String id = "pout_" + payoutId.toString().replace("-", "").substring(0, 14);
    String ref = "RPX-" + payoutId.toString().substring(0, 8).toUpperCase();
    return PayoutResult.ok(id, ref);
  }
}
