package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MetricCollectionJob {

  private final MetricCollectionService collection;

  public MetricCollectionJob(MetricCollectionService collection) {
    this.collection = collection;
  }

  /** Every minute Asia/Kolkata; samples stored as UTC minute buckets. */
  @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
  public void run() {
    collection.collectAndEvaluate();
  }
}
