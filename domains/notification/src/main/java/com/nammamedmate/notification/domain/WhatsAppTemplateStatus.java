package com.nammamedmate.notification.domain;

public enum WhatsAppTemplateStatus {
  APPROVED,
  PENDING,
  REJECTED;

  public static WhatsAppTemplateStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
    try {
      return WhatsAppTemplateStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
  }
}
