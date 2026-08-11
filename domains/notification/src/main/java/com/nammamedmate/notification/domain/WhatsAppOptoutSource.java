package com.nammamedmate.notification.domain;

public enum WhatsAppOptoutSource {
  WA_REPLY,
  IN_APP;

  public static WhatsAppOptoutSource parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_OPTOUT_SOURCE");
    }
    try {
      return WhatsAppOptoutSource.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_OPTOUT_SOURCE");
    }
  }
}
