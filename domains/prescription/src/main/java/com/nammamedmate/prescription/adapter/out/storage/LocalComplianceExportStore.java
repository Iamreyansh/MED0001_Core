package com.nammamedmate.prescription.adapter.out.storage;

import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * ponytail: local CSV export store until S3 PutObject + presign is shared for compliance exports
 * (ceiling: single-node tmp; upgrade: S3 + PresignedUrlService).
 */
@Component
public class LocalComplianceExportStore implements ComplianceExportStore {

  private final Path base;
  private final String downloadBaseUrl;

  public LocalComplianceExportStore() {
    this(
        Path.of(System.getProperty("java.io.tmpdir"), "medmate-compliance-exports"),
        "file://" + Path.of(System.getProperty("java.io.tmpdir"), "medmate-compliance-exports"));
  }

  public LocalComplianceExportStore(Path base, String downloadBaseUrl) {
    this.base = base;
    this.downloadBaseUrl = downloadBaseUrl;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Files.write(base.resolve(sanitize(key)), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store compliance export: " + key, e);
    }
  }

  @Override
  public String createDownloadUrl(String key, Duration ttl) {
    String object = key.startsWith(StorageObjectKeys.EXPORTS) ? key : StorageObjectKeys.export(key);
    return downloadBaseUrl + "/" + sanitize(object) + "?ttl=" + ttl.getSeconds();
  }

  private static String sanitize(String key) {
    return key.replace('/', '-');
  }
}
