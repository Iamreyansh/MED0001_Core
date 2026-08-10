package com.nammamedmate.marketing.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly SYSTEM segment recompute at 02:00 Asia/Kolkata (AC-2). */
@Component
@ConditionalOnProperty(
    name = "medmate.marketing.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SegmentNightlyScheduler {

  private final SegmentComputeService computeService;

  public SegmentNightlyScheduler(SegmentComputeService computeService) {
    this.computeService = computeService;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void recomputeSystemSegments() {
    computeService.computeAllSystemSegments();
  }
}
