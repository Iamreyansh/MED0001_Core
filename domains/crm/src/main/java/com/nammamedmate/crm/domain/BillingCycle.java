package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public final class BillingCycle {

  public static final String MONTHLY = "MONTHLY";
  public static final String ANNUAL = "ANNUAL";

  private BillingCycle() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      return MONTHLY;
    }
    String v = raw.trim().toUpperCase();
    if (!MONTHLY.equals(v) && !ANNUAL.equals(v)) {
      throw new AppException("VALIDATION_ERROR", "billing_cycle must be MONTHLY or ANNUAL", 400);
    }
    return v;
  }

  public static Instant advance(Instant from, String cycle) {
    ZonedDateTime z = from.atZone(ZoneOffset.UTC);
    Period p = ANNUAL.equals(cycle) ? Period.ofYears(1) : Period.ofMonths(1);
    return z.plus(p).toInstant();
  }

  public static long cyclePricePaise(long monthlyPaise, String cycle) {
    return ANNUAL.equals(cycle) ? CrmMoney.annualPaise(monthlyPaise) : monthlyPaise;
  }
}
