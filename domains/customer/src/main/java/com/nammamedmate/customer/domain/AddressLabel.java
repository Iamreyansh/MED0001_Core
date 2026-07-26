package com.nammamedmate.customer.domain;

import java.util.Locale;

public enum AddressLabel {
  HOME,
  WORK,
  OTHER;

  public static AddressLabel parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("label is required");
    }
    try {
      return AddressLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("label must be one of: HOME, WORK, OTHER");
    }
  }
}
