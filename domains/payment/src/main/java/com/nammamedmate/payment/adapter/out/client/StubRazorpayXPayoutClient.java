package com.nammamedmate.payment.adapter.out.client;

import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import java.util.UUID;

/** Deterministic RazorpayX payout stub for local/test. */
public final class StubRazorpayXPayoutClient implements RazorpayXPayoutPort {

  public static final String DEFAULT_KEY_ID = "rzp_test_x_stub";
  public static final String DEFAULT_KEY_SECRET = "test_razorpayx_secret";

  private final boolean failPayout;

  public StubRazorpayXPayoutClient() {
    this(false);
  }

  public StubRazorpayXPayoutClient(boolean failPayout) {
    this.failPayout = failPayout;
  }

  @Override
  public PayoutResult initiatePayout(PayoutRequest request) {
    if (failPayout) {
      throw new com.nammamedmate.kernel.error.AppException(
          "RAZORPAY_PAYOUT_FAILED", "Stub RazorpayX payout failure", 502);
    }
    String payoutId = "pout_stub_" + UUID.randomUUID().toString().substring(0, 8);
    return new PayoutResult(payoutId, 4);
  }
}
