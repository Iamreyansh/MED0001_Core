package com.nammamedmate.prescription.application.port.out;

import java.time.Duration;

public interface ComplianceExportStore {

  void put(String key, byte[] bytes, String contentType);

  String createDownloadUrl(String key, Duration ttl);
}
