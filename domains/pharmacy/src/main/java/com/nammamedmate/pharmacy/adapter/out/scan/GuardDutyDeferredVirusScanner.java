package com.nammamedmate.pharmacy.adapter.out.scan;

import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Staging/prod: GuardDuty Malware Protection for S3 scans {@code kyc/} objects after PutObject.
 * Request-path scan is intentionally a no-op; infected objects are soft-deleted by the worker when
 * EventBridge delivers {@code THREATS_FOUND} (or non-clean terminal statuses).
 */
@Component
@Profile({"prod", "staging"})
public class GuardDutyDeferredVirusScanner implements VirusScanner {

  @Override
  public void scan(byte[] content, String fileName) {
    // GuardDuty scans asynchronously after S3 put — see KycMalwareScanResultService.
  }
}
