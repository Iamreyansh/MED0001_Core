package com.nammamedmate.pharmacy.adapter.out.payout;

import com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort;
import java.util.UUID;

/** ponytail: fake payout id until RazorpayX integration (EPIC-022). */
public final class StubRazorpayXPayoutClient implements RazorpayXPayoutPort {

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    String payoutId = "pout_stub_" + UUID.randomUUID().toString().substring(0, 8);
    return new PayoutResult(payoutId, 4);
  }
}
