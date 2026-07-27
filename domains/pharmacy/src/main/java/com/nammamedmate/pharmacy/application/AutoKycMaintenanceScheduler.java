package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries transient KYC errors and times out stale async checks. */
@Component
@ConditionalOnProperty(
    name = "medmate.kyc.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AutoKycMaintenanceScheduler {

  private final AutoKycRetryWorker retryWorker;
  private final AutoKycService autoKyc;

  public AutoKycMaintenanceScheduler(AutoKycRetryWorker retryWorker, AutoKycService autoKyc) {
    this.retryWorker = retryWorker;
    this.autoKyc = autoKyc;
  }

  @Scheduled(fixedDelayString = "${medmate.kyc.jobs.retry-delay-ms:30000}")
  public void runRetryAndTimeoutJobs() {
    retryWorker.processDueRetries();
    autoKyc.processStaleAsyncChecks();
  }
}
