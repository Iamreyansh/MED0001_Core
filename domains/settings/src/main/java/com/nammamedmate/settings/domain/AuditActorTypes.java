package com.nammamedmate.settings.domain;

import java.util.Locale;
import java.util.Set;

public final class AuditActorTypes {

  public static final String ADMIN = "ADMIN";
  public static final String SYSTEM = "SYSTEM";
  public static final String AUTOMATION = "AUTOMATION";

  private static final Set<String> ALLOWED = Set.of(ADMIN, SYSTEM, AUTOMATION);

  private AuditActorTypes() {}

  public static boolean isValid(String value) {
    return value != null && ALLOWED.contains(value.trim().toUpperCase(Locale.ROOT));
  }

  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return ADMIN;
    }
    String n = value.trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED.contains(n)) {
      throw new IllegalArgumentException("actor_type must be ADMIN, SYSTEM, or AUTOMATION");
    }
    return n;
  }
}
