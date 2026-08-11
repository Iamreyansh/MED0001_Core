package com.nammamedmate.analytics.application.port.out;

import java.time.Duration;
import java.time.Instant;

/** CSV/report object store + time-limited download URL (local stub or S3 bridge). */
public interface AnalyticsExportPort {

  record SignedUrl(String url, Instant expiresAt) {}

  void put(String objectKey, byte[] bytes, String contentType);

  SignedUrl signedGet(String objectKey, Duration ttl);
}
