package com.nammamedmate.integration.domain;

/** Auto-select: IMPS for ≤ Rs 2,00,000 (2_00_00_000 paise); NEFT above. */
public final class PayoutModes {

  public static final String IMPS = "IMPS";
  public static final String NEFT = "NEFT";
  public static final String UPI = "UPI";

  /** Rs 2,00,000 in paise. */
  public static final long IMPS_MAX_PAISE = 20_000_000L;

  private PayoutModes() {}

  public static String autoSelect(long amountPaise) {
    return amountPaise <= IMPS_MAX_PAISE ? IMPS : NEFT;
  }
}
