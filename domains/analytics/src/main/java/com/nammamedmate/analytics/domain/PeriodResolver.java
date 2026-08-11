package com.nammamedmate.analytics.domain;

import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

/** Resolves analytics period selectors to IST midnight-bounded windows (store/query in UTC). */
public final class PeriodResolver {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int MAX_CUSTOM_DAYS = 365;
  private static final Set<String> OVERVIEW_PERIODS = Set.of("TODAY", "7D", "30D", "90D", "CUSTOM");
  private static final Set<String> LEADERBOARD_PERIODS = Set.of("7D", "30D", "90D");

  /** Growth KPIs / acquisition — no TODAY (STORY-003). */
  private static final Set<String> GROWTH_PERIODS = Set.of("7D", "30D", "90D", "CUSTOM");

  /** Order-trend — fixed presets only. */
  private static final Set<String> GROWTH_TREND_PERIODS = Set.of("7D", "30D", "90D");

  /** Pharmacy analytics — 7D/30D/12M/FY/CUSTOM (STORY-004). */
  private static final Set<String> PHARMACY_PERIODS = Set.of("7D", "30D", "12M", "FY", "CUSTOM");

  /** Geography overview — TODAY/7D/30D only; zone boundaries not versioned (STORY-005). */
  private static final Set<String> GEOGRAPHY_PERIODS = Set.of("TODAY", "7D", "30D");

  /** Supply-gap — 7D/30D only. */
  private static final Set<String> GEOGRAPHY_GAP_PERIODS = Set.of("7D", "30D");

  private PeriodResolver() {}

  public record DateWindow(
      String period,
      LocalDate fromDate,
      LocalDate toDate,
      Instant fromInclusive,
      Instant toExclusive,
      boolean live) {

    public Instant dateToDisplayInstant() {
      return toExclusive.minusSeconds(1);
    }

    public long dayCount() {
      return ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    }

    public DateWindow priorWindow(Clock clock) {
      if ("TODAY".equals(period)) {
        LocalDate priorDay = fromDate.minusDays(7);
        Instant from = priorDay.atStartOfDay(IST).toInstant();
        Instant to = priorDay.plusDays(1).atStartOfDay(IST).toInstant();
        Instant now = clock.instant();
        Instant priorSameTime =
            Instant.ofEpochMilli(
                from.toEpochMilli() + (now.toEpochMilli() - fromInclusive.toEpochMilli()));
        Instant toLive = priorSameTime.isBefore(to) ? priorSameTime : to;
        return new DateWindow("TODAY", priorDay, priorDay, from, toLive, true);
      }
      long days = dayCount();
      LocalDate priorTo = fromDate.minusDays(1);
      LocalDate priorFrom = priorTo.minusDays(days - 1);
      Instant from = priorFrom.atStartOfDay(IST).toInstant();
      Instant to = priorTo.plusDays(1).atStartOfDay(IST).toInstant();
      return new DateWindow(period, priorFrom, priorTo, from, to, false);
    }
  }

  public static DateWindow resolveOverview(
      String periodRaw, String dateFrom, String dateTo, Clock clock) {
    String period = normalizeRequired(periodRaw, OVERVIEW_PERIODS);
    LocalDate today = LocalDate.now(clock.withZone(IST));
    if ("CUSTOM".equals(period)) {
      if (isBlank(dateFrom) || isBlank(dateTo)) {
        throw new AppException(
            "MISSING_DATE_RANGE", "CUSTOM period requires date_from and date_to", 400);
      }
      LocalDate fromDate = parseDate(dateFrom);
      LocalDate toDate = parseDate(dateTo);
      if (fromDate.isAfter(toDate)) {
        throw new AppException("INVALID_PERIOD", "date_from is after date_to", 400);
      }
      long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
      if (days > MAX_CUSTOM_DAYS) {
        throw new AppException("DATE_RANGE_TOO_LARGE", "CUSTOM range exceeds 365 days", 422);
      }
      Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
      Instant toExclusive = toDate.plusDays(1).atStartOfDay(IST).toInstant();
      return new DateWindow(period, fromDate, toDate, fromInclusive, toExclusive, false);
    }
    if ("TODAY".equals(period)) {
      Instant fromInclusive = today.atStartOfDay(IST).toInstant();
      Instant toExclusive = clock.instant();
      return new DateWindow(period, today, today, fromInclusive, toExclusive, true);
    }
    int days =
        switch (period) {
          case "30D" -> 30;
          case "90D" -> 90;
          default -> 7;
        };
    LocalDate toDate = today;
    LocalDate fromDate = today.minusDays(days);
    Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
    Instant toExclusive = toDate.plusDays(1).atStartOfDay(IST).toInstant();
    return new DateWindow(period, fromDate, toDate, fromInclusive, toExclusive, false);
  }

  public static DateWindow resolveLeaderboard(String periodRaw, Clock clock) {
    String period = normalizeRequired(periodRaw, LEADERBOARD_PERIODS);
    return resolveOverview(period, null, null, clock);
  }

  /** Growth KPI / acquisition windows: 7D/30D/90D/CUSTOM (rejects TODAY). */
  public static DateWindow resolveGrowth(
      String periodRaw, String dateFrom, String dateTo, Clock clock) {
    String period = normalizeRequired(periodRaw, GROWTH_PERIODS);
    return resolveOverview(period, dateFrom, dateTo, clock);
  }

  /** Order-trend windows: 7D/30D/90D only. */
  public static DateWindow resolveGrowthTrend(String periodRaw, Clock clock) {
    String period = normalizeRequired(periodRaw, GROWTH_TREND_PERIODS);
    return resolveOverview(period, null, null, clock);
  }

  /**
   * Pharmacy analytics windows: 7D/30D/12M/FY/CUSTOM. FY = Apr 1 of current IST financial year
   * through today (AC-009); before Apr 1 uses previous FY start.
   */
  public static DateWindow resolvePharmacy(
      String periodRaw, String dateFrom, String dateTo, Clock clock) {
    String period = normalizeRequired(periodRaw, PHARMACY_PERIODS);
    LocalDate today = LocalDate.now(clock.withZone(IST));
    if ("CUSTOM".equals(period)) {
      return resolveOverview("CUSTOM", dateFrom, dateTo, clock);
    }
    if ("FY".equals(period)) {
      LocalDate fromDate = AnalyticsMath.indianFyStart(today);
      Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
      Instant toExclusive = today.plusDays(1).atStartOfDay(IST).toInstant();
      return new DateWindow(period, fromDate, today, fromInclusive, toExclusive, false);
    }
    if ("12M".equals(period)) {
      LocalDate fromDate = today.minusMonths(12);
      Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
      Instant toExclusive = today.plusDays(1).atStartOfDay(IST).toInstant();
      return new DateWindow(period, fromDate, today, fromInclusive, toExclusive, false);
    }
    // 7D / 30D — reuse overview day math
    return resolveOverview(period, null, null, clock);
  }

  public static boolean useAggregated(DateWindow window) {
    return "90D".equals(window.period())
        || "12M".equals(window.period())
        || "FY".equals(window.period())
        || window.dayCount() >= 90;
  }

  /** Geography overview windows: TODAY/7D/30D (rejects 90D/CUSTOM). */
  public static DateWindow resolveGeography(String periodRaw, Clock clock) {
    String period = normalizeRequired(periodRaw, GEOGRAPHY_PERIODS);
    return resolveOverview(period, null, null, clock);
  }

  /** Supply-gap windows: 7D/30D only. */
  public static DateWindow resolveGeographyGap(String periodRaw, Clock clock) {
    String period = normalizeRequired(periodRaw, GEOGRAPHY_GAP_PERIODS);
    return resolveOverview(period, null, null, clock);
  }

  private static String normalizeRequired(String periodRaw, Set<String> allowed) {
    if (isBlank(periodRaw)) {
      throw new AppException("INVALID_PERIOD", "period is required", 400);
    }
    String period = periodRaw.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(period)) {
      throw new AppException("INVALID_PERIOD", "period not in allowed set", 400);
    }
    return period;
  }

  private static LocalDate parseDate(String raw) {
    try {
      String v = raw.trim();
      return LocalDate.parse(v.substring(0, Math.min(10, v.length())));
    } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
      throw new AppException("MISSING_DATE_RANGE", "date_from/date_to must be ISO-8601 dates", 400);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
