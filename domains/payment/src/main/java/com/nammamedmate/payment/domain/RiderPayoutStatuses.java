package com.nammamedmate.payment.domain;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

/** Maps V042 rider_payouts statuses ↔ EPIC-012 finance API statuses. */
public final class RiderPayoutStatuses {

  public static final String STORAGE_PENDING = "PENDING";
  public static final String STORAGE_HELD = "HELD";
  public static final String STORAGE_RELEASED = "RELEASED";
  public static final String STORAGE_FAILED = "FAILED";
  public static final String STORAGE_BELOW = "BELOW_THRESHOLD_CARRIED_FORWARD";

  public static final String API_PENDING = "PENDING";
  public static final String API_HELD = "HELD";
  public static final String API_RELEASED = "RELEASED";
  public static final String API_FAILED = "FAILED";
  public static final String API_BELOW = "BELOW_THRESHOLD_CARRIED";

  /** Rs 100 minimum release threshold (paise). */
  public static final long MIN_RELEASE_PAISE = 10_000L;

  /** Default bulk-release ceiling Rs 10,000 (paise). */
  public static final long DEFAULT_BULK_MAX_PAISE = 1_000_000L;

  /** Default COD float hold limit Rs 2,000 (paise). */
  public static final long DEFAULT_COD_FLOAT_LIMIT_PAISE = 200_000L;

  private RiderPayoutStatuses() {}

  public static String toApiStatus(String storageStatus) {
    if (storageStatus == null) {
      return API_PENDING;
    }
    return switch (storageStatus) {
      case STORAGE_BELOW -> API_BELOW;
      default -> storageStatus;
    };
  }

  public static String toStorageFilter(String apiStatus) {
    if (apiStatus == null || apiStatus.isBlank()) {
      return null;
    }
    return switch (apiStatus.trim().toUpperCase(Locale.ROOT)) {
      case "PENDING" -> STORAGE_PENDING;
      case "HELD" -> STORAGE_HELD;
      case "RELEASED" -> STORAGE_RELEASED;
      case "FAILED" -> STORAGE_FAILED;
      case "BELOW_THRESHOLD_CARRIED", "BELOW_THRESHOLD_CARRIED_FORWARD" -> STORAGE_BELOW;
      default -> throw new IllegalArgumentException("INVALID_STATUS");
    };
  }

  /** ISO week label for cycle start, e.g. {@code 2026-W29}. */
  public static String isoWeekLabel(LocalDate cycleFrom) {
    if (cycleFrom == null) {
      return "";
    }
    WeekFields wf = WeekFields.ISO;
    int week = cycleFrom.get(wf.weekOfWeekBasedYear());
    int year = cycleFrom.get(wf.weekBasedYear());
    return String.format(Locale.ROOT, "%d-W%02d", year, week);
  }
}
