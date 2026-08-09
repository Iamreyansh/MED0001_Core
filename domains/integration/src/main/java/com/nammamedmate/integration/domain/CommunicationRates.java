package com.nammamedmate.integration.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Per-message rates in INR (story BR-3 / AC-004). */
public final class CommunicationRates {

  public static final BigDecimal SMS = new BigDecimal("0.12");
  public static final BigDecimal WHATSAPP_UTILITY = new BigDecimal("0.85");
  public static final BigDecimal WHATSAPP_MARKETING = new BigDecimal("2.00");
  public static final BigDecimal EMAIL = new BigDecimal("0.005");
  public static final BigDecimal PUSH = BigDecimal.ZERO;

  private CommunicationRates() {}

  public static BigDecimal rateFor(String channel, String template) {
    String ch = CommunicationChannels.normalize(channel);
    if (CommunicationChannels.SMS.equals(ch)) {
      return SMS;
    }
    if (CommunicationChannels.EMAIL.equals(ch)) {
      return EMAIL;
    }
    if (CommunicationChannels.PUSH.equals(ch)) {
      return PUSH;
    }
    if (CommunicationChannels.WHATSAPP.equals(ch)) {
      String t = template == null ? "" : template.toUpperCase(Locale.ROOT);
      if (t.contains("MARKETING")) {
        return WHATSAPP_MARKETING;
      }
      return WHATSAPP_UTILITY;
    }
    return BigDecimal.ZERO;
  }

  public static BigDecimal cost(String channel, String template, int count) {
    return rateFor(channel, template)
        .multiply(BigDecimal.valueOf(count))
        .setScale(2, RoundingMode.HALF_UP);
  }
}
