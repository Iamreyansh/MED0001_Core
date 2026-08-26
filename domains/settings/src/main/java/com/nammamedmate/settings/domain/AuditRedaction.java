package com.nammamedmate.settings.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Redacts configured sensitive keys from audit JSON snapshots. */
public final class AuditRedaction {

  public static final String REDACTED = "[REDACTED]";

  public static final Set<String> SENSITIVE_FIELDS =
      Set.of(
          "password_hash",
          "otp_hash",
          "totp_secret",
          "backup_codes",
          "gateway_token_id",
          "upi_id",
          "password");

  private AuditRedaction() {}

  @SuppressWarnings("unchecked")
  public static Object redact(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        String key = String.valueOf(e.getKey());
        if (SENSITIVE_FIELDS.contains(key.toLowerCase(Locale.ROOT))) {
          out.put(key, REDACTED);
        } else {
          out.put(key, redact(e.getValue()));
        }
      }
      return out;
    }
    if (value instanceof Collection<?> col) {
      List<Object> out = new ArrayList<>(col.size());
      for (Object item : col) {
        out.add(redact(item));
      }
      return out;
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> redactMap(Map<String, Object> value) {
    Object redacted = redact(value);
    if (redacted == null) {
      return null;
    }
    return (Map<String, Object>) redacted;
  }
}
