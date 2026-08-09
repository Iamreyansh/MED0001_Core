package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Proactive GSP JWT refresh ~1h before 24h expiry (story Notes). */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.gsp-token-refresh.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GspTokenRefreshScheduler {

  private final EinvoiceService service;

  public GspTokenRefreshScheduler(EinvoiceService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.integration.gsp-token-refresh-delay-ms:3600000}")
  public void refresh() {
    try {
      service.refreshTokenIfNeeded();
    } catch (RuntimeException ignored) {
      // service already logged CRITICAL + outbox alert
    }
  }
}
