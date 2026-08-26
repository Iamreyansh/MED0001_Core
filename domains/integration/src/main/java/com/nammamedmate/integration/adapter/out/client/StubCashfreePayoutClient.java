package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.CashfreePayoutClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic CashfreePayout stub when keys are blank. */
public final class StubCashfreePayoutClient implements CashfreePayoutClientPort {

  public static final String DEFAULT_KEY_ID = "cf_payouts_test_stub";
  public static final String DEFAULT_KEY_SECRET = "test_cashfree_payouts_secret";

  private final AtomicBoolean failPayout;
  private final boolean failFundAccount;
  private final boolean insufficientBalance;

  public StubCashfreePayoutClient() {
    this(false, false, false);
  }

  public StubCashfreePayoutClient(boolean failPayout) {
    this(failPayout, false, false);
  }

  public StubCashfreePayoutClient(
      boolean failPayout, boolean failFundAccount, boolean insufficientBalance) {
    this.failPayout = new AtomicBoolean(failPayout);
    this.failFundAccount = failFundAccount;
    this.insufficientBalance = insufficientBalance;
  }

  public void setFailPayout(boolean fail) {
    failPayout.set(fail);
  }

  @Override
  public BeneficiaryResult createBeneficiary(CreateBeneficiaryRequest request) {
    if (failFundAccount) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "CashfreePayout API error", 503);
    }
    String contact = "cont_stub_" + shortId();
    String fa = "fa_stub_" + shortId();
    return new BeneficiaryResult(contact, fa);
  }

  @Override
  public PayoutResult createPayout(CreatePayoutRequest request) {
    if (insufficientBalance) {
      throw new AppException(
          "INSUFFICIENT_BALANCE", "CashfreePayout account balance insufficient", 422);
    }
    if (failPayout.get()) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "CashfreePayout API error", 503);
    }
    return new PayoutResult("pout_stub_" + shortId(), "processing");
  }

  private static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
