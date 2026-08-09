package com.nammamedmate.integration.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CommunicationTemplates {

  private static final Map<String, Set<String>> BY_CHANNEL =
      Map.of(
          CommunicationChannels.PUSH, Set.of("TEST_PUSH"),
          CommunicationChannels.SMS, Set.of("OTP_VERIFICATION"),
          CommunicationChannels.WHATSAPP,
              Set.of("OTP_VERIFICATION", "UTILITY_ORDER_UPDATE", "MARKETING_PROMO"),
          CommunicationChannels.EMAIL, Set.of("TEST_EMAIL", "OTP_VERIFICATION"));

  private CommunicationTemplates() {}

  public static boolean isValid(String channel, String template) {
    if (template == null || template.isBlank()) {
      return false;
    }
    Set<String> allowed = BY_CHANNEL.get(CommunicationChannels.normalize(channel));
    return allowed != null && allowed.contains(template.toUpperCase(Locale.ROOT));
  }

  public static String normalize(String template) {
    return template == null ? null : template.toUpperCase(Locale.ROOT);
  }
}
