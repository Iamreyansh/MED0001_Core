package com.nammamedmate.support.application;

import com.nammamedmate.kernel.error.AppException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canned-response template vars — missing values leave the `{placeholder}` intact. */
final class CannedTemplate {

  static final Set<String> ALLOWED =
      Set.of("customer_name", "order_id", "refund_amount", "pharmacy_name", "ticket_id");

  private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

  private CannedTemplate() {}

  static void validate(String body) {
    if (body == null) {
      return;
    }
    Matcher m = VAR.matcher(body);
    while (m.find()) {
      String name = m.group(1).toLowerCase(Locale.ROOT);
      if (!ALLOWED.contains(name)) {
        throw new AppException(
            "INVALID_TEMPLATE_VARIABLE",
            "Unrecognised template variable {" + m.group(1) + "}",
            422);
      }
    }
  }

  static String interpolate(String body, Map<String, String> values) {
    if (body == null || body.isEmpty()) {
      return body;
    }
    Map<String, String> vals = values == null ? Map.of() : values;
    Matcher m = VAR.matcher(body);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String key = m.group(1).toLowerCase(Locale.ROOT);
      String replacement = vals.get(key);
      if (replacement == null || replacement.isBlank()) {
        m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
      } else {
        m.appendReplacement(out, Matcher.quoteReplacement(replacement));
      }
    }
    m.appendTail(out);
    return out.toString();
  }

  static Map<String, String> context(
      String customerName,
      String orderId,
      String refundAmount,
      String pharmacyName,
      String ticketId) {
    Map<String, String> m = new LinkedHashMap<>();
    if (customerName != null && !customerName.isBlank()) {
      m.put("customer_name", customerName);
    }
    if (orderId != null && !orderId.isBlank()) {
      m.put("order_id", orderId);
    }
    if (refundAmount != null && !refundAmount.isBlank()) {
      m.put("refund_amount", refundAmount);
    }
    if (pharmacyName != null && !pharmacyName.isBlank()) {
      m.put("pharmacy_name", pharmacyName);
    }
    if (ticketId != null && !ticketId.isBlank()) {
      m.put("ticket_id", ticketId);
    }
    return m;
  }

  static String formatRefundPaise(Long paise) {
    if (paise == null) {
      return null;
    }
    long rupees = paise / 100;
    long frac = Math.abs(paise % 100);
    return "₹" + rupees + "." + (frac < 10 ? "0" : "") + frac;
  }
}
