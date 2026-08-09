package com.nammamedmate.integration.domain;

import java.util.Locale;
import java.util.Set;

public final class CommunicationChannels {

  public static final String PUSH = "PUSH";
  public static final String SMS = "SMS";
  public static final String WHATSAPP = "WHATSAPP";
  public static final String EMAIL = "EMAIL";

  private static final Set<String> ALL = Set.of(PUSH, SMS, WHATSAPP, EMAIL);

  private CommunicationChannels() {}

  public static boolean isValid(String value) {
    return value != null && ALL.contains(value.toUpperCase(Locale.ROOT));
  }

  public static String normalize(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }
}
