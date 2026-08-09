package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic RazorpayX stub when keys are blank. */
public final class StubRazorpayXClient implements RazorpayXClientPort {

  public static final String DEFAULT_KEY_ID = "rzp_test_x_stub";
  public static final String DEFAULT_KEY_SECRET = "test_razorpayx_secret";

  private final AtomicBoolean failPayout;
  private final boolean failFundAccount;
  private final boolean insufficientBalance;

  public StubRazorpayXClient() {
    this(false, false, false);
  }

  public StubRazorpayXClient(boolean failPayout) {
    this(failPayout, false, false);
  }

  public StubRazorpayXClient(
      boolean failPayout, boolean failFundAccount, boolean insufficientBalance) {
    this.failPayout = new AtomicBoolean(failPayout);
    this.failFundAccount = failFundAccount;
    this.insufficientBalance = insufficientBalance;
  }

  public void setFailPayout(boolean fail) {
    failPayout.set(fail);
  }

  @Override
  public FundAccountResult createFundAccount(CreateFundAccountRequest request) {
    if (failFundAccount) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX API error", 503);
    }
    String contact = "cont_stub_" + shortId();
    String fa = "fa_stub_" + shortId();
    return new FundAccountResult(contact, fa);
  }

  @Override
  public PayoutResult createPayout(CreatePayoutRequest request) {
    if (insufficientBalance) {
      throw new AppException("INSUFFICIENT_BALANCE", "RazorpayX account balance insufficient", 422);
    }
    if (failPayout.get()) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX API error", 503);
    }
    return new PayoutResult("pout_stub_" + shortId(), "processing");
  }

  private static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
