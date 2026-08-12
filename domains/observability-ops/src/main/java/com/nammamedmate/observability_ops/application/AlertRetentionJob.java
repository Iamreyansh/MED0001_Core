package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AlertRetentionJob {

  private final MetricCollectionService collection;

  public AlertRetentionJob(MetricCollectionService collection) {
    this.collection = collection;
  }

  /** Daily purge of alerts older than 90 days. */
  @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Kolkata")
  public void run() {
    collection.purgeOlderThanDays(90);
  }
}
