package com.nammamedmate.customer.domain;

import java.util.Locale;
import java.util.Set;

public final class PreferredLanguages {

  public static final Set<String> ALLOWED = Set.of("en", "kn", "hi", "ta", "te", "ml", "mr");

  private PreferredLanguages() {}

  public static boolean isAllowed(String code) {
    return code != null && ALLOWED.contains(code.toLowerCase(Locale.ROOT));
  }

  public static String normalize(String code) {
    return code.toLowerCase(Locale.ROOT);
  }
}
