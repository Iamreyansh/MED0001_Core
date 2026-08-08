package com.nammamedmate.rider.domain;

import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** COD float limit + risk helpers (EPIC-011/STORY-007). */
public final class CodFloatLimits {

  public static final long DEFAULT_LIMIT_PAISE = 200_000L; // ₹2000
  public static final String CONFIG_KEY = "cod_float_limit_default";

  private CodFloatLimits() {}

  public static long resolvePaise(PlatformPricingConfigStore config) {
    if (config == null) {
      return DEFAULT_LIMIT_PAISE;
    }
    return config.get(CONFIG_KEY).map(CodFloatLimits::parsePaise).orElse(DEFAULT_LIMIT_PAISE);
  }

  static long parsePaise(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LIMIT_PAISE;
    }
    try {
      String t = raw.trim();
      // Accept plain paise integers or rupee decimals (e.g. "2000.00").
      if (t.contains(".")) {
        return new BigDecimal(t)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
      }
      return Long.parseLong(t);
    } catch (RuntimeException e) {
      return DEFAULT_LIMIT_PAISE;
    }
  }

  /** BR-002 / AC-001: exceeds limit → FLOAT_RISK. */
  public static boolean isFloatRisk(long codInHandPaise, long limitPaise) {
    return codInHandPaise > limitPaise;
  }

  /** AC-004: at or above limit → cannot accept new COD. */
  public static boolean canAcceptCod(long codInHandPaise, long limitPaise) {
    return codInHandPaise < limitPaise;
  }

  public static String riskStatus(long codInHandPaise, long limitPaise) {
    return isFloatRisk(codInHandPaise, limitPaise) ? "FLOAT_RISK" : "SAFE";
  }

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  public static long rupeesToPaise(Object amount) {
    if (amount == null) {
      throw new IllegalArgumentException("amount required");
    }
    BigDecimal bd;
    if (amount instanceof BigDecimal b) {
      bd = b;
    } else if (amount instanceof Number n) {
      bd = BigDecimal.valueOf(n.doubleValue());
    } else {
      bd = new BigDecimal(String.valueOf(amount).trim());
    }
    return bd.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
  }
}
