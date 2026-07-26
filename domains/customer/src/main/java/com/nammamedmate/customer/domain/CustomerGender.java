package com.nammamedmate.customer.domain;

import java.util.Locale;

public enum CustomerGender {
  MALE,
  FEMALE,
  OTHER,
  PREFER_NOT_TO_SAY;

  public static CustomerGender parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return CustomerGender.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid gender: " + raw);
    }
  }
}
