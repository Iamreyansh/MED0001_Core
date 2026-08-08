package com.nammamedmate.settings.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public final class ConfigValueTypes {

  public static final String INTEGER = "integer";
  public static final String DECIMAL = "decimal";
  public static final String BOOLEAN = "boolean";
  public static final String STRING = "string";
  public static final Set<String> ALL = Set.of(INTEGER, DECIMAL, BOOLEAN, STRING);

  private ConfigValueTypes() {}

  public static Object parse(String type, String raw) {
    if (raw == null) {
      throw new AppException("VALIDATION_ERROR", "Config value cannot be null", 400);
    }
    String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    return switch (t) {
      case INTEGER -> Integer.parseInt(raw.trim());
      case DECIMAL -> new BigDecimal(raw.trim());
      case BOOLEAN -> {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v)) {
          yield true;
        }
        if ("false".equals(v)) {
          yield false;
        }
        throw new IllegalArgumentException("not a boolean: " + raw);
      }
      case STRING -> raw;
      default -> throw new AppException("VALIDATION_ERROR", "Unknown config type: " + type, 400);
    };
  }

  /**
   * Validate a PATCH body value against the expected type; return canonical storage string.
   *
   * @param keyFull full key for error messages (e.g. orders.delivery_fee)
   */
  public static String validateAndSerialize(String keyFull, String type, Object value) {
    if (value == null) {
      throw new AppException("VALIDATION_ERROR", keyFull + " value cannot be null", 400);
    }
    String shortName = shortName(keyFull);
    String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    return switch (t) {
      case INTEGER -> serializeInteger(shortName, value);
      case DECIMAL -> serializeDecimal(shortName, value);
      case BOOLEAN -> serializeBoolean(shortName, value);
      case STRING -> serializeString(shortName, value);
      default -> throw new AppException("VALIDATION_ERROR", "Unknown config type: " + type, 400);
    };
  }

  private static String serializeInteger(String shortName, Object value) {
    if (value instanceof Integer i) {
      return Integer.toString(i);
    }
    if (value instanceof Long l) {
      if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
        throw typeError(shortName, INTEGER);
      }
      return Long.toString(l);
    }
    if (value instanceof BigDecimal bd) {
      try {
        return Integer.toString(bd.intValueExact());
      } catch (ArithmeticException ex) {
        throw typeError(shortName, INTEGER);
      }
    }
    if (value instanceof Double d) {
      if (d % 1 != 0 || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
        throw typeError(shortName, INTEGER);
      }
      return Integer.toString(d.intValue());
    }
    throw typeError(shortName, INTEGER);
  }

  private static String serializeDecimal(String shortName, Object value) {
    if (value instanceof BigDecimal bd) {
      return bd.stripTrailingZeros().toPlainString();
    }
    if (value instanceof Integer i) {
      return Integer.toString(i);
    }
    if (value instanceof Long l) {
      return Long.toString(l);
    }
    if (value instanceof Double d) {
      return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
    if (value instanceof Float f) {
      return BigDecimal.valueOf(f.doubleValue()).stripTrailingZeros().toPlainString();
    }
    throw typeError(shortName, DECIMAL);
  }

  private static String serializeBoolean(String shortName, Object value) {
    if (value instanceof Boolean b) {
      return Boolean.toString(b);
    }
    throw typeError(shortName, BOOLEAN);
  }

  private static String serializeString(String shortName, Object value) {
    if (value instanceof String s) {
      return s;
    }
    throw typeError(shortName, STRING);
  }

  private static AppException typeError(String shortName, String expected) {
    return new AppException("VALIDATION_ERROR", shortName + " expects " + expected, 400);
  }

  private static String shortName(String keyFull) {
    if (keyFull == null) {
      return "value";
    }
    int dot = keyFull.lastIndexOf('.');
    return dot >= 0 && dot < keyFull.length() - 1 ? keyFull.substring(dot + 1) : keyFull;
  }
}
