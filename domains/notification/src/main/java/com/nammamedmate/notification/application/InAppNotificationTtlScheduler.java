package com.nammamedmate.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly in-app notification TTL cleanup in Asia/Kolkata (EPIC-017 STORY-006). */
@Component
@ConditionalOnProperty(
    name = "medmate.notification.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InAppNotificationTtlScheduler {

  private final InAppNotificationService notifications;

  public InAppNotificationTtlScheduler(InAppNotificationService notifications) {
    this.notifications = notifications;
  }

  @Scheduled(cron = "0 10 2 * * *", zone = "Asia/Kolkata")
  public void cleanup() {
    notifications.runTtlCleanup();
  }
}
