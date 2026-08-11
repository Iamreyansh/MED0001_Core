package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CustomerNotificationPreferences(
    UUID id,
    UUID customerId,
    boolean pushEnabled,
    boolean smsEnabled,
    boolean whatsappEnabled,
    boolean emailEnabled,
    boolean catOrderUpdates,
    boolean catAccountCritical,
    boolean catPromotions,
    boolean catRefillReminders,
    boolean catOffers,
    Instant createdAt,
    Instant updatedAt) {

  public static CustomerNotificationPreferences defaults(UUID id, UUID customerId, Instant at) {
    return new CustomerNotificationPreferences(
        id, customerId, true, true, true, true, true, true, true, true, true, at, at);
  }

  public Map<String, Object> snapshot() {
    Map<String, Object> channels = new LinkedHashMap<>();
    channels.put("push", pushEnabled);
    channels.put("sms", smsEnabled);
    channels.put("whatsapp", whatsappEnabled);
    channels.put("email", emailEnabled);
    Map<String, Object> categories = new LinkedHashMap<>();
    categories.put("order_updates", catOrderUpdates);
    categories.put("account_critical", catAccountCritical);
    categories.put("promotions", catPromotions);
    categories.put("refill_reminders", catRefillReminders);
    categories.put("offers", catOffers);
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
      case "order_updates", "transactional" -> catOrderUpdates;
      case "account_critical" -> catAccountCritical;
      case "promotions", "promotional", "marketing" -> catPromotions;
      case "refill_reminders", "lifecycle" -> catRefillReminders;
      case "offers" -> catOffers;
      default -> true;
    };
  }
}
