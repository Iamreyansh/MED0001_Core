package com.nammamedmate.payment.domain;

/** API ↔ storage status / refund_to mapping for EPIC-012 STORY-005. */
public final class RefundStatuses {

  public static final String API_PENDING = "PENDING";
  public static final String API_PROCESSING = "PROCESSING";
  public static final String API_COMPLETED = "COMPLETED";
  public static final String API_FAILED = "FAILED";

  public static final String STORAGE_PENDING = "PENDING";
  public static final String STORAGE_INITIATED = "INITIATED";
  public static final String STORAGE_PROCESSED = "PROCESSED";
  public static final String STORAGE_FAILED = "FAILED";

  public static final String API_SOURCE = "SOURCE_ACCOUNT";
  public static final String API_WALLET = "WALLET";
  public static final String STORAGE_SOURCE = "SOURCE";
  public static final String STORAGE_WALLET = "WALLET";

  /** Auto-approve threshold: ₹500 = 50_000 paise (BR-002). */
  public static final long AUTO_REFUND_MAX_PAISE = 50_000L;

  public static final int OVERDUE_HOURS = 24;
  public static final int EXPECTED_BUSINESS_DAYS = 5;

  private RefundStatuses() {}

  public static String toApiStatus(String storage) {
    if (storage == null || storage.isBlank()) {
      return API_PENDING;
    }
    return switch (storage.trim().toUpperCase()) {
      case STORAGE_PENDING -> API_PENDING;
      case STORAGE_INITIATED -> API_PROCESSING;
      case STORAGE_PROCESSED -> API_COMPLETED;
      case STORAGE_FAILED -> API_FAILED;
      default -> storage.trim().toUpperCase();
    };
  }

  public static String toStorageStatusFilter(String apiStatus) {
    if (apiStatus == null || apiStatus.isBlank()) {
      return null;
    }
    return switch (apiStatus.trim().toUpperCase()) {
      case API_PENDING -> STORAGE_PENDING;
      case API_PROCESSING -> STORAGE_INITIATED;
      case API_COMPLETED -> STORAGE_PROCESSED;
      case API_FAILED -> STORAGE_FAILED;
      default -> throw new IllegalArgumentException("invalid refund status");
    };
  }

  public static String toApiRefundTo(String storage) {
    if (storage == null || storage.isBlank()) {
      return API_WALLET;
    }
    return switch (storage.trim().toUpperCase()) {
      case STORAGE_SOURCE, "SOURCE_ACCOUNT" -> API_SOURCE;
      case STORAGE_WALLET -> API_WALLET;
      default -> storage.trim().toUpperCase();
    };
  }

  public static String toStorageRefundToFilter(String apiRefundTo) {
    if (apiRefundTo == null || apiRefundTo.isBlank()) {
      return null;
    }
    return switch (apiRefundTo.trim().toUpperCase()) {
      case API_SOURCE, STORAGE_SOURCE -> STORAGE_SOURCE;
      case API_WALLET -> STORAGE_WALLET;
      default -> throw new IllegalArgumentException("invalid refund_to");
    };
  }

  public static String customerMessage(String apiStatus, String apiRefundTo) {
    if (API_COMPLETED.equals(apiStatus)) {
      if (API_WALLET.equals(apiRefundTo)) {
        return "Your refund has been credited to your Namma Money wallet.";
      }
      return "Your refund has been credited to your original payment method.";
    }
    if (API_PROCESSING.equals(apiStatus) || API_PENDING.equals(apiStatus)) {
      return "Your refund is being processed and will be credited to your original payment method"
          + " within 3-5 business days.";
    }
    if (API_FAILED.equals(apiStatus)) {
      return "Your refund could not be completed. Please contact support.";
    }
    return "Your refund is being processed.";
  }
}
