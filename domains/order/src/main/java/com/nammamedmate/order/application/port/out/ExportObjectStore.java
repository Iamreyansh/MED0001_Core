package com.nammamedmate.order.application.port.out;

/** CSV export object store (S3 in prod; local stub elsewhere). */
public interface ExportObjectStore {

  void put(String key, byte[] bytes, String contentType);

  /** Time-limited download URL (presigned GET or local stub). */
  String createDownloadUrl(String key);
}
