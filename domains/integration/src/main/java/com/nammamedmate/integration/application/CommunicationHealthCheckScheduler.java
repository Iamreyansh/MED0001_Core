package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Channel health ping every 5 minutes (story BR-1). */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.comms-health-check.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CommunicationHealthCheckScheduler {

  private final CommunicationService service;

  public CommunicationHealthCheckScheduler(CommunicationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.integration.comms-health-check-delay-ms:300000}")
  public void tick() {
    try {
      service.runHealthChecks();
    } catch (RuntimeException ignored) {
      // next tick retries
    }
  }
}
