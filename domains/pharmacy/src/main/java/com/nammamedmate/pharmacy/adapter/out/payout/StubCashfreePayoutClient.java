package com.nammamedmate.pharmacy.adapter.out.payout;

import com.nammamedmate.pharmacy.application.port.out.CashfreePayoutPort;
import java.util.UUID;

/** ponytail: fake payout id until CashfreePayout integration (EPIC-022). */
public final class StubCashfreePayoutClient implements CashfreePayoutPort {

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    String payoutId = "pout_stub_" + UUID.randomUUID().toString().substring(0, 8);
    return new PayoutResult(payoutId, 4);
  }
}
