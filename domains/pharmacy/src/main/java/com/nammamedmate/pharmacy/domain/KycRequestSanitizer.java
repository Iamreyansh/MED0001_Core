package com.nammamedmate.pharmacy.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Redacts secrets from KYC API request payloads before persistence. */
public final class KycRequestSanitizer {

  private static final Set<String> SECRET_KEYS =
      Set.of(
          "api_key",
          "apikey",
          "authorization",
          "auth_token",
          "token",
          "secret",
          "password",
          "x_api_key");

  private KycRequestSanitizer() {}

  public static Map<String, Object> sanitise(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      String key = entry.getKey();
      if (key == null) {
        continue;
      }
      if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
        out.put(key, "[REDACTED]");
      } else if (entry.getValue() instanceof Map<?, ?> nested) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) nested;
        out.put(key, sanitise(nestedMap));
      } else {
        out.put(key, entry.getValue());
      }
    }
    return Collections.unmodifiableMap(out);
  }
}
