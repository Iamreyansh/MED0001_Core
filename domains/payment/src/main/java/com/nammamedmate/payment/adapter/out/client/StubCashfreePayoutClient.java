package com.nammamedmate.payment.adapter.out.client;

import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import java.util.UUID;

/** Deterministic CashfreePayout payout stub for local/test. */
public final class StubCashfreePayoutClient implements CashfreePayoutPort {

  public static final String DEFAULT_KEY_ID = "cf_payouts_test_stub";
  public static final String DEFAULT_KEY_SECRET = "test_cashfree_payouts_secret";

  private final boolean failPayout;

  public StubCashfreePayoutClient() {
    this(false);
  }

  public StubCashfreePayoutClient(boolean failPayout) {
    this.failPayout = failPayout;
  }

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    if (failPayout) {
      throw new com.nammamedmate.kernel.error.AppException(
          "CASHFREE_PAYOUT_FAILED", "Stub CashfreePayout payout failure", 502);
    }
    String payoutId = "pout_stub_" + UUID.randomUUID().toString().substring(0, 8);
    return new PayoutResult(payoutId, 4);
  }
}
