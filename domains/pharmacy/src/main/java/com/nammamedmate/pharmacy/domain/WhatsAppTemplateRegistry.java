package com.nammamedmate.pharmacy.domain;

import java.util.Set;

/** Pre-approved Meta WhatsApp template names for pharmacy notices. */
public final class WhatsAppTemplateRegistry {

  public static final Set<String> APPROVED =
      Set.of(
          "PHARMACY_GENERAL_NOTICE",
          "PHARMACY_URGENT_ALERT",
          "PHARMACY_COMPLIANCE_WARNING",
          "PHARMACY_PERFORMANCE_NOTICE");

  private WhatsAppTemplateRegistry() {}

  public static boolean isApproved(String templateName) {
    return templateName != null && APPROVED.contains(templateName.trim());
  }
}
