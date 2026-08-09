package com.nammamedmate.integration.application.port.out;

import java.time.Duration;

/** Tally XML export object store (local stub / S3 bridge). */
public interface AccountingExportObjectStore {

  void put(String key, byte[] bytes, String contentType);

  String createDownloadUrl(String key, Duration ttl);
}
