package com.nammamedmate.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared analytics percentage helpers. Story BR text uses {@code - 100}; locked epic decision is
 * {@code × 100} to match sample KPI values.
 */
public final class AnalyticsMath {

  private AnalyticsMath() {}

  /** (numerator / denominator) × 100, scale 1. Zero denom → 0.0. */
  public static BigDecimal ratioPct(long numerator, long denominator) {
    if (denominator == 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
  }

  /** Week-over-week / prior-window delta as percent change × 100. */
  public static BigDecimal wowDeltaPct(long current, long prior) {
    if (prior == 0L) {
      return current == 0L
          ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
          : BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(current - prior)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(prior), 1, RoundingMode.HALF_UP);
  }

  /** take_rate_pct = (commission / GMV) × 100. */
  public static BigDecimal takeRatePct(long commissionPaise, long gmvPaise) {
    return ratioPct(commissionPaise, gmvPaise);
  }

  /** repeat_customer_pct = (repeat / active) × 100. */
  public static BigDecimal repeatCustomerPct(long repeatCustomers, long activeCustomers) {
    return ratioPct(repeatCustomers, activeCustomers);
  }

  /** net_margin_pct = (net_revenue − cogs) / net_revenue × 100. */
  public static BigDecimal netMarginPct(long netRevenuePaise, long cogsEstimatePaise) {
    if (netRevenuePaise == 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(netRevenuePaise - cogsEstimatePaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(netRevenuePaise), 1, RoundingMode.HALF_UP);
  }

  public static long aovPaise(long gmvPaise, long ordersCount) {
    if (ordersCount <= 0L) {
      return 0L;
    }
    return gmvPaise / ordersCount;
  }

  public static long netRevenuePaise(long gmvPaise, long refundsPaise, long cancellationsPaise) {
    return Math.max(0L, gmvPaise - refundsPaise - cancellationsPaise);
  }

  /** Indian financial year start (Apr 1) containing {@code onDate} in IST calendar. */
  public static java.time.LocalDate indianFyStart(java.time.LocalDate onDate) {
    int year = onDate.getMonthValue() >= 4 ? onDate.getYear() : onDate.getYear() - 1;
    return java.time.LocalDate.of(year, 4, 1);
  }

  /**
   * Drop-off from previous funnel stage: ((prev − current) / prev) × 100. Null when first stage.
   */
  public static BigDecimal dropOffPct(Long previousCount, long currentCount) {
    if (previousCount == null) {
      return null;
    }
    if (previousCount == 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return ratioPct(previousCount - currentCount, previousCount);
  }

  /** Average minutes from total seconds / count (scale 1). */
  public static BigDecimal avgMinutes(long totalSeconds, long count) {
    if (count <= 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(totalSeconds)
        .divide(BigDecimal.valueOf(60L * count), 1, RoundingMode.HALF_UP);
  }

  /**
   * CAC in whole rupees: total_spend_rs / new_users. Zero users → 0. ORGANIC callers should short-
   * circuit to 0 before invoking.
   */
  public static long cacRs(BigDecimal spendRs, long newUsers) {
    if (newUsers <= 0L || spendRs == null || spendRs.signum() <= 0) {
      return 0L;
    }
    return spendRs.divide(BigDecimal.valueOf(newUsers), 0, RoundingMode.HALF_UP).longValue();
  }

  /** Retention (retained / cohort_size) × 100 at scale 2 for storage. */
  public static BigDecimal retentionPct(long retained, long cohortSize) {
    if (cohortSize <= 0L) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(retained)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(cohortSize), 2, RoundingMode.HALF_UP);
  }

  /** ISO week label e.g. {@code 2026-W17} for a calendar date in IST. */
  public static String isoWeekLabel(java.time.LocalDate date) {
    java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.ISO;
    int week = date.get(wf.weekOfWeekBasedYear());
    int year = date.get(wf.weekBasedYear());
    return String.format(java.util.Locale.ROOT, "%04d-W%02d", year, week);
  }

  /** Monday of the ISO week containing {@code date}. */
  public static java.time.LocalDate isoWeekStart(java.time.LocalDate date) {
    return date.with(java.time.DayOfWeek.MONDAY);
  }

  /** First ISO week (Monday) of the calendar month containing {@code date}. */
  public static java.time.LocalDate firstIsoWeekOfMonth(java.time.LocalDate date) {
    return isoWeekStart(date.withDayOfMonth(1));
  }

  /** Supply-demand gap % = (demand − supply) / demand × 100. Surplus or zero demand → 0.0 (LOW). */
  public static BigDecimal gapPct(BigDecimal demandScore, BigDecimal supplyScore) {
    if (demandScore == null || demandScore.signum() <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    BigDecimal supply = supplyScore == null ? BigDecimal.ZERO : supplyScore;
    BigDecimal gap = demandScore.subtract(supply);
    if (gap.signum() <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return gap.multiply(BigDecimal.valueOf(100)).divide(demandScore, 1, RoundingMode.HALF_UP);
  }

  /**
   * Gap severity: CRITICAL (&gt;50%), HIGH (30–50%), MODERATE (10–30%), LOW (&lt;10%). Dark zones
   * force CRITICAL (AC-006).
   */
  public static String gapSeverity(BigDecimal gapPct, boolean isDark) {
    if (isDark) {
      return "CRITICAL";
    }
    double g = gapPct == null ? 0.0 : gapPct.doubleValue();
    if (g > 50.0) {
      return "CRITICAL";
    }
    if (g >= 30.0) {
      return "HIGH";
    }
    if (g >= 10.0) {
      return "MODERATE";
    }
    return "LOW";
  }

  /**
   * Remediation suggestion: ADD_PHARMACIES when coverage &lt; 60%; EXPAND_ZONE when unserved
   * attempts &gt; 0; ADD_RIDERS when CRITICAL/HIGH and coverage &gt; 80%.
   */
  public static String supplyGapSuggestion(
      String severity, BigDecimal pharmacyCoveragePct, int unservedAttempts) {
    if (pharmacyCoveragePct != null && pharmacyCoveragePct.compareTo(BigDecimal.valueOf(60)) < 0) {
      return "ADD_PHARMACIES";
    }
    if (unservedAttempts > 0) {
      return "EXPAND_ZONE";
    }
    if (("CRITICAL".equals(severity) || "HIGH".equals(severity))
        && pharmacyCoveragePct != null
        && pharmacyCoveragePct.compareTo(BigDecimal.valueOf(80)) > 0) {
      return "ADD_RIDERS";
    }
    return null;
  }

  /** Average hourly order demand: orders / (days × 24). */
  public static BigDecimal demandScore(long ordersCount, long dayCount) {
    if (dayCount <= 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(ordersCount)
        .divide(BigDecimal.valueOf(dayCount * 24L), 1, RoundingMode.HALF_UP);
  }

  /**
   * Supply score ≈ avg riders × pharmacy coverage (0–1). Ponytail ceiling: ignores intra-day rider
   * curves; upgrade → weight by business-hour rider samples.
   */
  public static BigDecimal supplyScore(BigDecimal avgRidersOnline, BigDecimal pharmacyCoveragePct) {
    BigDecimal riders = avgRidersOnline == null ? BigDecimal.ZERO : avgRidersOnline;
    BigDecimal coverage =
        pharmacyCoveragePct == null
            ? BigDecimal.ZERO
            : pharmacyCoveragePct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    return riders.multiply(coverage).setScale(1, RoundingMode.HALF_UP);
  }

  /** day_of_week 0=Sunday … 6=Saturday → English weekday name, or null. */
  public static String dayOfWeekName(Integer dayOfWeek) {
    if (dayOfWeek == null || dayOfWeek < 0 || dayOfWeek > 6) {
      return null;
    }
    return switch (dayOfWeek) {
      case 0 -> "SUNDAY";
      case 1 -> "MONDAY";
      case 2 -> "TUESDAY";
      case 3 -> "WEDNESDAY";
      case 4 -> "THURSDAY";
      case 5 -> "FRIDAY";
      default -> "SATURDAY";
    };
  }
}
