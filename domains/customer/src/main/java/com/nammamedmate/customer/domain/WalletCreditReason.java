package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum WalletCreditReason {
  REFUND,
  GOODWILL,
  PROMOTIONAL,
  /** System-only referral reward credit (not accepted on admin credit API). */
  REFERRAL;

  private static final String ADMIN_REASONS =
      "REFUND, GOODWILL, PROMOTIONAL, ADMIN_CREDIT, CASHBACK";

  /**
   * Admin credit reasons. Story aliases: {@code ADMIN_CREDIT}→GOODWILL, {@code
   * CASHBACK}→PROMOTIONAL. REFERRAL is system-disbursed.
   */
  public static WalletCreditReason require(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_REASON", "reason must be one of: " + ADMIN_REASONS, 422);
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if ("ADMIN_CREDIT".equals(normalized)) {
      return GOODWILL;
    }
    if ("CASHBACK".equals(normalized)) {
      return PROMOTIONAL;
    }
    WalletCreditReason reason;
    try {
      reason = WalletCreditReason.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_REASON", "reason must be one of: " + ADMIN_REASONS, 422);
    }
    if (reason == REFERRAL) {
      throw new AppException("INVALID_REASON", "reason must be one of: " + ADMIN_REASONS, 422);
    }
    return reason;
  }

  /** System / internal credit reasons (EPIC-012 wallet credit). */
  public static WalletCreditReason requireSystem(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_REASON", "reason is required", 422);
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "REFUND" -> REFUND;
      case "ADMIN_CREDIT" -> GOODWILL;
      case "CASHBACK" -> PROMOTIONAL;
      case "GOODWILL" -> GOODWILL;
      case "PROMOTIONAL" -> PROMOTIONAL;
      case "REFERRAL", "REFERRAL_REWARD" -> REFERRAL;
      default -> throw new AppException("INVALID_REASON", "reason not in allowed enum", 422);
    };
  }
}
