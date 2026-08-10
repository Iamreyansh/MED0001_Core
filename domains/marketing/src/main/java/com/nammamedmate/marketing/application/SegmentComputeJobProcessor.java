package com.nammamedmate.marketing.application;

import com.nammamedmate.marketing.application.port.out.SegmentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls QUEUED segment compute jobs.
 *
 * <p>ponytail: in-process async via scheduler (not SQS); upgrade to apps/worker outbox when volume
 * warrants it.
 */
@Component
@ConditionalOnProperty(
    name = "medmate.marketing.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SegmentComputeJobProcessor {

  private final SegmentStore store;
  private final SegmentComputeService computeService;

  public SegmentComputeJobProcessor(SegmentStore store, SegmentComputeService computeService) {
    this.store = store;
    this.computeService = computeService;
  }

  @Scheduled(fixedDelayString = "${medmate.marketing.compute-job.poll-delay-ms:2000}")
  public void pollQueuedJobs() {
    for (SegmentStore.ComputeJob job : store.findQueuedJobs(5)) {
      processJob(job.id());
    }
  }

  public void processJob(java.util.UUID jobId) {
    SegmentStore.ComputeJob job = store.findJob(jobId).orElse(null);
    if (job == null || !"QUEUED".equals(job.status())) {
      return;
    }
    java.time.Instant now = java.time.Instant.now();
    store.markJobRunning(jobId, now);
    try {
      computeService.computeSegment(job.segmentId());
      store.markJobCompleted(jobId, java.time.Instant.now());
    } catch (RuntimeException ex) {
      store.markJobFailed(jobId, java.time.Instant.now(), ex.getMessage());
      throw ex;
    }
  }
}
