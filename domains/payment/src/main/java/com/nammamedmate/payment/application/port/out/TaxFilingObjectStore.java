package com.nammamedmate.payment.application.port.out;

/** Filing export object store (local stub / S3 bridge). */
public interface TaxFilingObjectStore {

  void put(String key, byte[] bytes, String contentType);

  /** Time-limited download URL. */
  String createDownloadUrl(String key, java.time.Duration ttl);
}
