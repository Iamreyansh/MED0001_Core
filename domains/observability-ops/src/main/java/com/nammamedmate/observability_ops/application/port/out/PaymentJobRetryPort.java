package com.nammamedmate.observability_ops.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaymentJobRetryPort {

  record FailedJob(UUID jobId, Instant failedAt, int failedRetryCount) {}

  boolean jobExists(UUID jobId);

  List<FailedJob> jobsReadyForRetry(Instant now, int delayMinutes, int maxRetries);

  /**
   * @return true if retry succeeded
   */
  boolean retry(UUID jobId);

  void markExhausted(UUID jobId);

  int failedRetryCount(UUID jobId);
}
