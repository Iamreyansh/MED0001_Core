package com.nammamedmate.pharmacy.domain;

import java.util.Set;
import java.util.regex.Pattern;

/** Indian PAN: [A-Z]{5}[0-9]{4}[A-Z]; 4th char is entity type. */
public final class Pan {

  private static final Pattern PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
  private static final Set<Character> ENTITY_TYPES =
      Set.of('P', 'C', 'F', 'H', 'A', 'T', 'B', 'L', 'J', 'G');

  private Pan() {}

  public static String requireValid(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("INVALID_PAN");
    }
    String pan = raw.trim().toUpperCase();
    if (!PATTERN.matcher(pan).matches() || !ENTITY_TYPES.contains(pan.charAt(3))) {
      throw new IllegalArgumentException("INVALID_PAN");
    }
    return pan;
  }
}
