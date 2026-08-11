package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PharmacyNotificationPreferences(
    UUID id,
    UUID pharmacyId,
    boolean pushEnabled,
    boolean smsEnabled,
    boolean whatsappEnabled,
    boolean emailEnabled,
    boolean catOrderAlerts,
    boolean catSettlementUpdates,
    boolean catKycUpdates,
    boolean catLowStockAlerts,
    boolean catComplianceReminders,
    Instant createdAt,
    Instant updatedAt) {

  public static PharmacyNotificationPreferences defaults(UUID id, UUID pharmacyId, Instant at) {
    return new PharmacyNotificationPreferences(
        id, pharmacyId, true, true, true, true, true, true, true, true, true, at, at);
  }

  public Map<String, Object> snapshot() {
    Map<String, Object> channels = new LinkedHashMap<>();
    channels.put("push", pushEnabled);
    channels.put("sms", smsEnabled);
    channels.put("whatsapp", whatsappEnabled);
    channels.put("email", emailEnabled);
    Map<String, Object> categories = new LinkedHashMap<>();
    categories.put("order_alerts", catOrderAlerts);
    categories.put("settlement_updates", catSettlementUpdates);
    categories.put("kyc_updates", catKycUpdates);
    categories.put("low_stock_alerts", catLowStockAlerts);
    categories.put("compliance_reminders", catComplianceReminders);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("channels", channels);
    out.put("categories", categories);
    return out;
  }

  public boolean channelEnabled(String channel) {
    if (channel == null) {
      return true;
    }
    return switch (channel.trim().toLowerCase()) {
      case "push" -> pushEnabled;
      case "sms" -> smsEnabled;
      case "whatsapp" -> whatsappEnabled;
      case "email" -> emailEnabled;
      default -> true;
    };
  }

  public boolean categoryEnabled(String category) {
    if (category == null || category.isBlank()) {
      return true;
    }
    String c = category.trim().toLowerCase();
    return switch (c) {
      case "order_alerts", "transactional", "order_updates" -> catOrderAlerts;
      case "settlement_updates" -> catSettlementUpdates;
      case "kyc_updates", "account_critical" -> catKycUpdates;
      case "low_stock_alerts", "promotions", "promotional" -> catLowStockAlerts;
      case "compliance_reminders" -> catComplianceReminders;
      default -> true;
    };
  }
}
