package com.nammamedmate.integration.domain;

import java.util.Locale;
import java.util.Set;

public final class CommunicationProviders {

  public static final String FIREBASE_FCM = "FIREBASE_FCM";
  public static final String MSG91 = "MSG91";
  public static final String TWILIO = "TWILIO";
  public static final String META_CLOUD_API = "META_CLOUD_API";
  public static final String SENDGRID = "SENDGRID";
  public static final String AWS_SES = "AWS_SES";

  private static final Set<String> ALL =
      Set.of(FIREBASE_FCM, MSG91, TWILIO, META_CLOUD_API, SENDGRID, AWS_SES);

  private CommunicationProviders() {}

  public static boolean isValid(String value) {
    return value != null && ALL.contains(value.toUpperCase(Locale.ROOT));
  }

  public static String normalize(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }
}
