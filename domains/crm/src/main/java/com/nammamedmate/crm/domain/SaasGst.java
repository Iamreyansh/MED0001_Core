package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** SaaS software services GST (SAC 9983). */
public final class SaasGst {

  public static final String SAC_CODE = "9983";
  public static final BigDecimal RATE_PCT = BigDecimal.valueOf(18);

  private SaasGst() {}

  /** GST paise on subtotal (half-up). */
  public static long gstPaise(long subtotalPaise) {
    if (subtotalPaise <= 0) {
      return 0L;
    }
    return BigDecimal.valueOf(subtotalPaise)
        .multiply(RATE_PCT)
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public static long totalWithGstPaise(long subtotalPaise) {
    return Math.addExact(subtotalPaise, gstPaise(subtotalPaise));
  }
}
