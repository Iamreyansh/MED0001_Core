package com.nammamedmate.analytics.adapter.out.storage;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * ponytail: local file store for analytics CSV until S3 PutObject + PresignedUrlService is wired in
 * apps/api (ceiling: single-node tmp; upgrade: S3 bridge like PaymentTaxBridgeConfig).
 */
public class LocalAnalyticsExportStore implements AnalyticsExportPort {

  private final Path base;
  private final String downloadBaseUrl;

  public LocalAnalyticsExportStore() {
    this(
        Path.of(System.getProperty("java.io.tmpdir"), "medmate-analytics-exports"),
        "file://" + Path.of(System.getProperty("java.io.tmpdir"), "medmate-analytics-exports"));
  }

  public LocalAnalyticsExportStore(Path base, String downloadBaseUrl) {
    this.base = base;
    this.downloadBaseUrl = downloadBaseUrl;
  }

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Files.write(base.resolve(sanitize(objectKey)), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store analytics export: " + objectKey, e);
    }
  }

  @Override
  public SignedUrl signedGet(String objectKey, Duration ttl) {
    String object = objectKey.contains("/") ? objectKey : StorageObjectKeys.export(objectKey);
    Instant expires = Instant.now().plus(ttl);
    String url = downloadBaseUrl + "/" + sanitize(object) + "?ttl=" + ttl.getSeconds();
    return new SignedUrl(url, expires);
  }

  private static String sanitize(String key) {
    return key.replace('/', '-');
  }
}
