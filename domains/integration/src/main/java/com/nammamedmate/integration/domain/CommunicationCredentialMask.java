package com.nammamedmate.integration.domain;

public final class CommunicationCredentialMask {

  private CommunicationCredentialMask() {}

  /** First 4 characters + {@code ****}; empty/null → {@code ****}. */
  public static String apiKeyPreview(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return "****";
    }
    String trimmed = apiKey.trim();
    if (trimmed.length() <= 4) {
      return trimmed + "****";
    }
    return trimmed.substring(0, 4) + "****";
  }
}
