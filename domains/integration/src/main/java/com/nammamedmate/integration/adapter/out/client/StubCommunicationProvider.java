package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.CommunicationProviderPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic communication provider stub.
 *
 * <ul>
 *   <li>Provider marked DOWN via {@link #markDown(String)} → health ping fails / send fails
 *   <li>api_key containing {@code fail} or {@code invalid} → connectivity test fails
 * </ul>
 */
public final class StubCommunicationProvider implements CommunicationProviderPort {

  private final Set<String> downProviders = ConcurrentHashMap.newKeySet();

  public void markDown(String provider) {
    if (provider != null) {
      downProviders.add(provider.toUpperCase(Locale.ROOT));
    }
  }

  public void markHealthy(String provider) {
    if (provider != null) {
      downProviders.remove(provider.toUpperCase(Locale.ROOT));
    }
  }

  public void clear() {
    downProviders.clear();
  }

  @Override
  public boolean healthPing(String provider) {
    return provider != null && !downProviders.contains(provider.toUpperCase(Locale.ROOT));
  }

  @Override
  public boolean connectivityTest(String provider, Map<String, String> credentials) {
    if (provider == null || credentials == null) {
      return false;
    }
    String apiKey = credentials.getOrDefault("api_key", "");
    String lower = apiKey.toLowerCase(Locale.ROOT);
    if (lower.contains("fail") || lower.contains("invalid") || lower.isBlank()) {
      return false;
    }
    return !downProviders.contains(provider.toUpperCase(Locale.ROOT));
  }

  @Override
  public SendResult sendTest(
      String provider, String channel, String recipient, String template, boolean isTest) {
    if (provider == null || downProviders.contains(provider.toUpperCase(Locale.ROOT))) {
      throw new AppException("PROVIDER_UNAVAILABLE", "Provider returned error on test send", 503);
    }
    // is_test labelled in caller's response/log; stub acknowledges.
    if (!isTest) {
      throw new AppException("PROVIDER_UNAVAILABLE", "Non-test sends are EPIC-017", 503);
    }
    return new SendResult(UUID.randomUUID(), "SENT");
  }
}
