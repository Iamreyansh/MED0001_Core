package com.nammamedmate.notification.domain;

public enum DevicePlatform {
  IOS,
  ANDROID;

  public static DevicePlatform parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("platform required");
    }
    try {
      return DevicePlatform.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_PLATFORM");
    }
  }
}
