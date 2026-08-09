package com.nammamedmate.integration.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface CommunicationProviderPort {

  /** Lightweight health ping (not a billed message). */
  boolean healthPing(String provider);

  /** Connectivity check with candidate credentials before save. */
  boolean connectivityTest(String provider, Map<String, String> credentials);

  SendResult sendTest(
      String provider, String channel, String recipient, String template, boolean isTest);

  record SendResult(UUID logId, String status) {}
}
