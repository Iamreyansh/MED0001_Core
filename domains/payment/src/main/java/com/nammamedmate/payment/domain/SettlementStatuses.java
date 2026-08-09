package com.nammamedmate.payment.domain;

/** Maps V019 storage statuses ↔ EPIC-012 finance API statuses. */
public final class SettlementStatuses {

  public static final String STORAGE_PENDING = "PENDING_RELEASE";
  public static final String STORAGE_RELEASED = "RELEASED";
  public static final String STORAGE_PAID = "PAID";
  public static final String STORAGE_HELD = "HELD";
  public static final String STORAGE_FAILED = "FAILED";
  public static final String STORAGE_BELOW = "BELOW_THRESHOLD_CARRIED";

  public static final String API_PENDING = "PENDING";
  public static final String API_RELEASED = "RELEASED";
  public static final String API_HELD = "HELD";
  public static final String API_BELOW = "BELOW_THRESHOLD_CARRIED";

  /** Rs 100 minimum release threshold (paise). */
  public static final long MIN_RELEASE_PAISE = 10_000L;

  /** Default bulk-release ceiling Rs 50,000 (paise). */
  public static final long DEFAULT_BULK_MAX_PAISE = 5_000_000L;

  private SettlementStatuses() {}

  /** API filter → storage status (null/blank → no filter). */
  public static String toStorageFilter(String apiStatus) {
    if (apiStatus == null || apiStatus.isBlank()) {
      return null;
    }
    return switch (apiStatus.trim().toUpperCase()) {
      case "PENDING" -> STORAGE_PENDING;
      case "RELEASED" -> STORAGE_RELEASED;
      case "HELD" -> STORAGE_HELD;
      case "BELOW_THRESHOLD_CARRIED" -> STORAGE_BELOW;
      case "PAID" -> STORAGE_PAID;
      case "FAILED" -> STORAGE_FAILED;
      case "PENDING_RELEASE" -> STORAGE_PENDING;
      default -> throw new IllegalArgumentException("INVALID_STATUS");
    };
  }

  /** Storage status → finance API status. */
  public static String toApiStatus(String storageStatus) {
    if (storageStatus == null) {
      return API_PENDING;
    }
    return switch (storageStatus) {
      case STORAGE_PENDING -> API_PENDING;
      case STORAGE_RELEASED, STORAGE_PAID -> API_RELEASED;
      case STORAGE_HELD -> API_HELD;
      case STORAGE_BELOW -> API_BELOW;
      case STORAGE_FAILED -> "FAILED";
      default -> storageStatus;
    };
  }
}
